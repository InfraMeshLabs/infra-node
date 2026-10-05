package com.inframesh.node.connection;

import com.inframesh.node.dto.NodeHealthResponse;
import com.inframesh.node.dto.connection.NodeEnvelope;
import com.inframesh.node.dto.connection.NodeError;
import com.inframesh.node.dto.connection.NodeHeartbeat;
import com.inframesh.node.dto.worker.WorkerRequest;
import com.inframesh.node.enums.NodeConnectionState;
import com.inframesh.node.enums.NodeMessageType;
import com.inframesh.node.monitor.ActiveRequestCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * WebSocket-based {@link OutboundNodeConnection} maintaining a persistent
 * connection from this node (Worker or Router) to InfraMesh Console.
 *
 * Owns every piece of connection infrastructure so node implementations do not:
 * handshake authentication headers, heartbeat, HEALTH reporting, reconnect with
 * exponential backoff + jitter, graceful shutdown, {@link NodeEnvelope} (de)serialization,
 * and dispatch of inbound messages to {@link NodeRequestHandler} /
 * {@link NodeStreamRequestHandler} / {@link NodeMessageHandler}. It never
 * interprets business payloads.
 *
 * It also keeps {@link ActiveRequestCounter} for the inference it runs: a
 * handler serving {@code WorkerRequest} is counted from the moment it starts
 * until it returns (REQUEST) or its stream ends (STREAM_REQUEST). Handlers for
 * any other payload - a Router's routing decisions - are never counted.
 *
 * Registered as a {@link SmartLifecycle} bean so Spring starts it once the
 * application context is up and stops it - in the order required by the
 * outbound protocol: forbid further reconnects, cancel any pending reconnect,
 * stop the heartbeat and HEALTH reporting, then close the socket - during
 * application shutdown.
 *
 * HEARTBEAT and HEALTH are deliberately separate: HEARTBEAT only proves the
 * connection is alive, HEALTH carries the node's runtime health
 * ({@link NodeHealthResponse}, the same payload DIRECT serves from
 * {@code GET /api/v1/health}) - {@code CONNECTED != HEALTHY}. Both are sent only
 * while CONNECTED and restart on every (re)connect. Their ACKs are delivered to
 * {@link NodeMessageHandler}s like any other non-REQUEST message and never change
 * connection state.
 *
 * A connection failure here never fails node startup or brings the process
 * down; it is only ever logged and retried.
 */
public class WebSocketOutboundNodeConnection implements OutboundNodeConnection, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WebSocketOutboundNodeConnection.class);

    static final String NODE_ID_HEADER = "X-Infra-Node-Id";
    static final String NODE_CREDENTIAL_HEADER = "X-Infra-Node-Credential";
    static final String CONNECT_PATH = "/ws/v1/nodes/connect";

    private static final TypeReference<NodeEnvelope<JsonNode>> ENVELOPE_TYPE = new TypeReference<>() {
    };

    private final NodeConnectionProperties properties;
    private final NodeRequestHandler<?, ?> requestHandler;
    private final List<NodeMessageHandler> messageHandlers;
    private final Supplier<NodeHealthResponse> healthSource;
    // Counts the REQUESTs being served; a detached counter nobody reads when they are not inference.
    private final ActiveRequestCounter requestCounter;
    private final ReconnectBackoff backoff;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final JsonMapper jsonMapper = JsonMapper.shared();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(daemonThreads("inframesh-outbound-connection"));
    // Health collection can block (CPU load is sampled over ~1s), so it runs on its own thread
    // and never delays heartbeats or reconnects on the connection scheduler.
    private final ScheduledExecutorService healthScheduler =
            Executors.newSingleThreadScheduledExecutor(daemonThreads("inframesh-outbound-health"));
    // Inbound messages are handled on a separate executor - a REQUEST (inference, routing decision)
    // can take long, and blocking the java.net.http.WebSocket listener thread would also delay
    // heartbeats, other REQUESTs and connection events. One thread per in-flight message, so no
    // pool size to tune (the SDK targets Java 17, so no virtual threads here).
    private final ExecutorService messageExecutor =
            Executors.newCachedThreadPool(daemonThreads("inframesh-outbound-request-"));
    // java.net.http.WebSocket does not allow a new sendText before the previous one completed -
    // heartbeat and RESPONSE sends can race from different threads, so every send is serialized
    // on this lock.
    private final Object sendLock = new Object();
    // null unless a NodeStreamRequestHandler is configured; STREAM_REQUEST/CANCEL then go to
    // NodeMessageHandlers only.
    private final OutboundStreamRegistry<?, ?> streams;

    private final AtomicReference<NodeConnectionState> state =
            new AtomicReference<>(NodeConnectionState.DISCONNECTED);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);

    private volatile WebSocket webSocket;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> healthTask;
    private volatile ScheduledFuture<?> reconnectTask;

    public WebSocketOutboundNodeConnection(NodeConnectionProperties properties) {
        this(properties, null, List.of());
    }

    /**
     * @param requestHandler  serves REQUESTs received over this connection; {@code null} if this node
     *                        does not serve requests over OUTBOUND (REQUESTs are then rejected with an
     *                        ERROR rather than silently ignored, so Console does not wait for a timeout).
     * @param messageHandlers receive every inbound envelope other than REQUEST (and STREAM_REQUEST,
     *                        when a {@link NodeStreamRequestHandler} serves it).
     */
    public WebSocketOutboundNodeConnection(NodeConnectionProperties properties,
                                           NodeRequestHandler<?, ?> requestHandler,
                                           List<NodeMessageHandler> messageHandlers) {
        this(properties, requestHandler, messageHandlers, null);
    }

    /**
     * @param healthSource collects this node's runtime health for periodic HEALTH messages;
     *                     {@code null} disables HEALTH reporting (the connection still heartbeats).
     */
    public WebSocketOutboundNodeConnection(NodeConnectionProperties properties,
                                           NodeRequestHandler<?, ?> requestHandler,
                                           List<NodeMessageHandler> messageHandlers,
                                           Supplier<NodeHealthResponse> healthSource) {
        this(properties, requestHandler, null, messageHandlers, healthSource, null);
    }

    /**
     * @param streamRequestHandler serves STREAM_REQUESTs received over this connection; {@code null}
     *                             leaves STREAM_REQUEST and CANCEL to {@code messageHandlers}.
     * @param activeRequestCounter counts the Worker inference requests ({@code WorkerRequest}) being
     *                             served by the two handlers; {@code null} disables counting.
     */
    public WebSocketOutboundNodeConnection(NodeConnectionProperties properties,
                                           NodeRequestHandler<?, ?> requestHandler,
                                           NodeStreamRequestHandler<?, ?> streamRequestHandler,
                                           List<NodeMessageHandler> messageHandlers,
                                           Supplier<NodeHealthResponse> healthSource,
                                           ActiveRequestCounter activeRequestCounter) {
        this.properties = properties;
        this.requestHandler = requestHandler;
        this.messageHandlers = List.copyOf(messageHandlers);
        this.healthSource = healthSource;
        this.requestCounter = requestHandler == null
                ? new ActiveRequestCounter()
                : inferenceCounter(activeRequestCounter, requestHandler.requestType());
        this.streams = streamRequestHandler == null
                ? null
                : OutboundStreamRegistry.create(this, streamRequestHandler, messageExecutor,
                        inferenceCounter(activeRequestCounter, streamRequestHandler.requestType()));
        this.backoff = new ReconnectBackoff(
                properties.getOutbound().getReconnect().getInitialDelay(),
                properties.getOutbound().getReconnect().getMaxDelay());
    }

    // ------------------------------------------------------------------
    // OutboundNodeConnection
    // ------------------------------------------------------------------

    @Override
    public void connect() {
        shuttingDown.set(false);
        doConnect();
    }

    @Override
    public void disconnect() {
        shuttingDown.set(true);
        cancelReconnect();
        stopHeartbeat();
        stopHealthReporting();
        state.set(NodeConnectionState.DISCONNECTED);
        cancelStreams("outbound connection closed");

        WebSocket ws = this.webSocket;
        this.webSocket = null;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
    }

    @Override
    public boolean isConnected() {
        return state.get() == NodeConnectionState.CONNECTED;
    }

    /**
     * Package-private test seam exposing the current lifecycle state; not part
     * of the public {@link OutboundNodeConnection} contract.
     */
    NodeConnectionState currentState() {
        return state.get();
    }

    /**
     * Package-private test seam: number of reconnects scheduled since the last successful connect.
     */
    int reconnectAttempts() {
        return backoff.attempts();
    }

    @Override
    public void send(NodeEnvelope<?> message) {
        WebSocket ws = this.webSocket;
        if (ws == null || state.get() != NodeConnectionState.CONNECTED) {
            log.debug("Skipping send, outbound connection not connected. type={}", message.type());
            return;
        }

        // join() waits for this send to complete before releasing the lock for the next one.
        synchronized (sendLock) {
            try {
                ws.sendText(jsonMapper.writeValueAsString(message), true).join();
            } catch (RuntimeException e) {
                log.warn("Failed to send message over outbound connection, type={}", message.type(), e);
            }
        }
    }

    // ------------------------------------------------------------------
    // SmartLifecycle
    // ------------------------------------------------------------------

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            connect();
        }
    }

    @Override
    public void stop() {
        stop(() -> { });
    }

    @Override
    public void stop(Runnable callback) {
        if (running.compareAndSet(true, false)) {
            log.info("Shutting down InfraMesh outbound connection, nodeId={}", properties.getNodeId());
            disconnect();
            scheduler.shutdownNow();
            // Not shutdownNow(): interrupting an in-flight CPU sample only produces a noisy warning.
            // Periodic tasks are dropped on shutdown, and a report still being collected finds the
            // connection DISCONNECTED and is never sent.
            healthScheduler.shutdown();
            messageExecutor.shutdownNow();
        }
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    // ------------------------------------------------------------------
    // Connect / reconnect
    // ------------------------------------------------------------------

    private void doConnect() {
        if (shuttingDown.get()) {
            return;
        }
        if (!connecting.compareAndSet(false, true)) {
            log.debug("Connect already in progress, skipping duplicate attempt");
            return;
        }

        try {
            log.info("Connecting to InfraMesh Console, nodeId={}", properties.getNodeId());

            // The credential travels only in this handshake header - never in the URL, an
            // envelope, a heartbeat payload, a log line or an exception message.
            httpClient.newWebSocketBuilder()
                    .header(NODE_ID_HEADER, String.valueOf(properties.getNodeId()))
                    .header(NODE_CREDENTIAL_HEADER, properties.getCredential())
                    .buildAsync(buildConnectUri(), new ConnectionListener())
                    .whenComplete((ws, error) -> {
                        connecting.set(false);
                        if (error != null) {
                            log.warn("Failed to connect to InfraMesh Console: {}", error.toString());
                            handleDisconnected(null);
                        }
                    });
        } catch (RuntimeException e) {
            connecting.set(false);
            log.warn("Failed to initiate connection to InfraMesh Console: {}", e.toString());
            handleDisconnected(null);
        }
    }

    /**
     * Handles loss of the connection (or a failed handshake, {@code source == null}).
     *
     * onClose, onError and a failed handshake can all report the same loss, possibly at once;
     * scheduleReconnect's CAS guard ensures only one reconnect task results, and the losers leave
     * the winner's RECONNECTING state alone. An event from an already-replaced connection
     * ({@code source} is not the current socket) is ignored so it cannot tear down the live one.
     */
    void handleDisconnected(WebSocket source) {
        WebSocket current = this.webSocket;
        if (source != null && current != null && current != source) {
            log.debug("Ignoring disconnect event from a replaced connection");
            return;
        }

        stopHeartbeat();
        stopHealthReporting();
        webSocket = null;
        // Their chunks have nowhere to go: Console already failed these streams with the connection.
        cancelStreams("outbound connection lost");
        log.info("Disconnected from InfraMesh Console");
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (shuttingDown.get()) {
            state.set(NodeConnectionState.DISCONNECTED);
            return;
        }
        if (!reconnectPending.compareAndSet(false, true)) {
            return;
        }

        state.set(NodeConnectionState.RECONNECTING);
        long delayMillis = backoff.nextDelayMillis();
        log.info("Scheduling reconnect to InfraMesh Console in {}ms", delayMillis);
        reconnectTask = scheduler.schedule(this::attemptReconnect, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void attemptReconnect() {
        reconnectPending.set(false);
        reconnectTask = null;
        if (shuttingDown.get()) {
            return;
        }

        // Same nodeId/credential as the initial connect - a reconnect never re-registers the node.
        log.info("Reconnecting to InfraMesh Console, nodeId={}", properties.getNodeId());
        doConnect();
    }

    private void cancelReconnect() {
        reconnectPending.set(false);
        ScheduledFuture<?> task = reconnectTask;
        if (task != null) {
            task.cancel(false);
            reconnectTask = null;
        }
    }

    private URI buildConnectUri() {
        URI base = URI.create(properties.getConsoleUrl());
        String scheme = switch (base.getScheme()) {
            case "http" -> "ws";
            case "https" -> "wss";
            case "ws", "wss" -> base.getScheme();
            default -> throw new IllegalStateException("Unsupported console-url scheme: " + base.getScheme());
        };

        try {
            return new URI(scheme, base.getUserInfo(), base.getHost(), base.getPort(), CONNECT_PATH, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid inframesh.node.console-url: " + properties.getConsoleUrl(), e);
        }
    }

    // ------------------------------------------------------------------
    // Heartbeat
    // ------------------------------------------------------------------

    private void startHeartbeat() {
        stopHeartbeat();
        long intervalMillis = properties.getOutbound().getHeartbeatInterval().toMillis();
        heartbeatTask = scheduler.scheduleAtFixedRate(
                this::sendHeartbeat, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
            heartbeatTask = null;
        }
    }

    private void sendHeartbeat() {
        if (state.get() != NodeConnectionState.CONNECTED) {
            return;
        }
        send(newEnvelope(NodeMessageType.HEARTBEAT, null, new NodeHeartbeat()));
    }

    // ------------------------------------------------------------------
    // Health reporting
    // ------------------------------------------------------------------

    // The first HEALTH goes out right after (re)connect so Console does not wait a full interval
    // to learn the node's runtime health.
    private void startHealthReporting() {
        stopHealthReporting();
        Duration interval = properties.getOutbound().getHealthInterval();
        if (healthSource == null || interval == null || interval.isZero() || interval.isNegative()) {
            return;
        }
        long intervalMillis = interval.toMillis();
        healthTask = healthScheduler.scheduleAtFixedRate(
                this::sendHealth, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void stopHealthReporting() {
        ScheduledFuture<?> task = healthTask;
        if (task != null) {
            task.cancel(false);
            healthTask = null;
        }
    }

    private void sendHealth() {
        if (state.get() != NodeConnectionState.CONNECTED) {
            return;
        }

        NodeHealthResponse health;
        try {
            health = healthSource.get();
        } catch (RuntimeException e) {
            // A failing collector must not cancel the periodic task (an exception escaping
            // scheduleAtFixedRate would) - skip this report and try again next interval.
            log.warn("Failed to collect node health, skipping HEALTH report: {}", e.toString());
            return;
        }

        // send() re-checks the state, so a report collected while the connection dropped is skipped.
        send(newEnvelope(NodeMessageType.HEALTH, null, health));
    }

    <T> NodeEnvelope<T> newEnvelope(NodeMessageType type, String requestId, T payload) {
        return NodeEnvelope.create(type, properties.getNodeId(), requestId, payload);
    }

    // Only Worker inference is an active request: REQUEST and STREAM_REQUEST carry a WorkerRequest
    // for a Worker, while a Router's handler (RoutingRequest) must leave the count untouched.
    private static ActiveRequestCounter inferenceCounter(ActiveRequestCounter counter, Class<?> requestType) {
        return counter != null && WorkerRequest.class.isAssignableFrom(requestType)
                ? counter
                : new ActiveRequestCounter();
    }

    private void cancelStreams(String reason) {
        if (streams != null) {
            streams.cancelAll(reason);
        }
    }

    private static ThreadFactory daemonThreads(String namePrefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            String name = namePrefix.endsWith("-") ? namePrefix + counter.getAndIncrement() : namePrefix;
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    // ------------------------------------------------------------------
    // WebSocket.Listener
    // ------------------------------------------------------------------

    private final class ConnectionListener implements WebSocket.Listener {

        // java.net.http.WebSocket may deliver one text message across several onText calls
        // (last=false until the final fragment); accumulate here rather than acting on each
        // fragment - a REQUEST payload (a full prompt) can easily span more than one frame.
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            if (shuttingDown.get()) {
                // A handshake that was in flight when disconnect()/stop() ran just
                // succeeded; honor the shutdown instead of resurrecting the connection.
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
                return;
            }

            WebSocketOutboundNodeConnection.this.webSocket = webSocket;
            backoff.reset();
            state.set(NodeConnectionState.CONNECTED);
            log.info("Connected to InfraMesh Console, nodeId={}", properties.getNodeId());
            startHeartbeat();
            startHealthReporting();
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            webSocket.request(1);

            if (last) {
                String json = buffer.toString();
                buffer.setLength(0);
                handleMessage(webSocket, json);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("WebSocket closed by InfraMesh Console, statusCode={}, reason={}", statusCode, reason);
            handleDisconnected(webSocket);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("WebSocket error on outbound connection to InfraMesh Console: {}", error.toString());
            handleDisconnected(webSocket);
        }
    }

    // ------------------------------------------------------------------
    // Inbound dispatch
    // ------------------------------------------------------------------

    // Never runs a handler on this (listener) thread - every handler call goes through
    // messageExecutor so heartbeats and connection events are never delayed by business code.
    private void handleMessage(WebSocket webSocket, String json) {
        NodeEnvelope<JsonNode> envelope;
        try {
            envelope = jsonMapper.readValue(json, ENVELOPE_TYPE);
        } catch (RuntimeException e) {
            log.debug("Failed to parse message from InfraMesh Console", e);
            return;
        }

        log.debug("Received message from InfraMesh Console, type={}", envelope.type());

        if (envelope.type() == NodeMessageType.REQUEST) {
            handleRequest(webSocket, envelope);
        } else if (streams != null && envelope.type() == NodeMessageType.STREAM_REQUEST) {
            streams.start(webSocket, envelope);
        } else {
            if (streams != null && envelope.type() == NodeMessageType.CANCEL) {
                messageExecutor.execute(() -> streams.cancel(envelope.requestId(), "CANCEL received"));
            }
            dispatchToMessageHandlers(envelope);
        }
    }

    private void handleRequest(WebSocket webSocket, NodeEnvelope<JsonNode> envelope) {
        String requestId = envelope.requestId();

        if (requestHandler == null) {
            log.warn("Received REQUEST but no NodeRequestHandler is configured, rejecting. requestId={}", requestId);
            sendIfSameConnection(webSocket, newEnvelope(NodeMessageType.ERROR, requestId,
                    new NodeError(NodeError.UNSUPPORTED_REQUEST, "Node does not have an outbound request handler configured")));
            return;
        }

        messageExecutor.execute(() -> sendIfSameConnection(webSocket, serve(requestHandler, envelope)));
    }

    private <Q, R> NodeEnvelope<?> serve(NodeRequestHandler<Q, R> handler, NodeEnvelope<JsonNode> envelope) {
        String requestId = envelope.requestId();

        Q request;
        try {
            request = jsonMapper.treeToValue(envelope.payload(), handler.requestType());
        } catch (RuntimeException e) {
            log.warn("Failed to parse REQUEST payload, requestId={}", requestId, e);
            return newEnvelope(NodeMessageType.ERROR, requestId,
                    new NodeError(NodeError.MALFORMED_PAYLOAD, "Malformed request payload"));
        }

        // Active only while the handler runs - sending the RESPONSE/ERROR afterwards is not inference.
        requestCounter.increment();
        try {
            return newEnvelope(NodeMessageType.RESPONSE, requestId, handler.handle(request));
        } catch (RuntimeException e) {
            log.warn("Outbound request failed, requestId={}", requestId, e);
            return newEnvelope(NodeMessageType.ERROR, requestId,
                    new NodeError(NodeError.REQUEST_FAILED, String.valueOf(e.getMessage())));
        } finally {
            requestCounter.decrement();
        }
    }

    private void dispatchToMessageHandlers(NodeEnvelope<JsonNode> envelope) {
        for (NodeMessageHandler handler : messageHandlers) {
            messageExecutor.execute(() -> {
                try {
                    handler.handle(envelope);
                } catch (RuntimeException e) {
                    log.warn("NodeMessageHandler failed, type={}, messageId={}", envelope.type(), envelope.messageId(), e);
                }
            });
        }
    }

    // If the connection was replaced (disconnect -> reconnect) while a REQUEST was being served,
    // the stale RESPONSE/ERROR is dropped rather than sent over the new connection - Console has
    // already failed that request with the old connection, and it must not be mistaken for a
    // response on the new one.
    void sendIfSameConnection(WebSocket originatingWebSocket, NodeEnvelope<?> envelope) {
        if (this.webSocket == originatingWebSocket) {
            send(envelope);
        } else {
            log.info("Dropping response for stale (replaced) connection, requestId={}", envelope.requestId());
        }
    }
}
