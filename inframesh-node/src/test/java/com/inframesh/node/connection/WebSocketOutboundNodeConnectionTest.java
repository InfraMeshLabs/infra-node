package com.inframesh.node.connection;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.NodeHealthResponse;
import com.inframesh.node.dto.connection.NodeEnvelope;
import com.inframesh.node.dto.connection.NodeError;
import com.inframesh.node.dto.connection.NodeStreamChunk;
import com.inframesh.node.dto.connection.NodeStreamComplete;
import com.inframesh.node.dto.worker.WorkerRequest;
import com.inframesh.node.enums.NodeStatus;
import com.inframesh.node.enums.NodeConnectionMode;
import com.inframesh.node.enums.NodeConnectionState;
import com.inframesh.node.enums.NodeMessageType;
import com.inframesh.node.monitor.ActiveRequestCounter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the SDK against a real socket ({@link FakeConsoleServer}). Payloads are a test-local
 * record on purpose: the connection must work for any node's business DTO (Worker, Router, ...).
 */
class WebSocketOutboundNodeConnectionTest {

    record EchoRequest(String text) {
    }

    record EchoResponse(String echoed) {
    }

    private FakeConsoleServer server;
    private WebSocketOutboundNodeConnection connection;

    @AfterEach
    void tearDown() throws IOException {
        if (connection != null) {
            connection.stop();
            connection.disconnect();
        }
        if (server != null) {
            server.close();
        }
    }

    // ------------------------------------------------------------------
    // Handshake / authentication
    // ------------------------------------------------------------------

    @Test
    void connect_sendsAuthHeadersAndReachesConnected() throws Exception {
        UUID nodeId = UUID.randomUUID();
        String credential = "test-credential-" + UUID.randomUUID();
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId).credential(credential));

        connection.connect();

        Map<String, String> headers = server.awaitHandshakeHeaders(5);
        assertThat(headers).isNotNull();
        assertThat(headers.get("X-Infra-Node-Id")).isEqualTo(nodeId.toString());
        assertThat(headers.get("X-Infra-Node-Credential")).isEqualTo(credential);

        awaitState(NodeConnectionState.CONNECTED);
        assertThat(connection.isConnected()).isTrue();
    }

    @Test
    void credential_neverAppearsInLogsOrFrames() throws Exception {
        String credential = "super-secret-credential-" + UUID.randomUUID();
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl()).credential(credential).heartbeat(Duration.ofMillis(100)));

        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);

        try {
            connection.connect();
            awaitState(NodeConnectionState.CONNECTED);
            String heartbeat = server.awaitTextFrame(5);
            assertThat(heartbeat).isNotNull().doesNotContain(credential);
            connection.disconnect();

            boolean leaked = appender.list.stream()
                    .anyMatch(event -> event.getFormattedMessage().contains(credential)
                            || (event.getThrowableProxy() != null
                            && String.valueOf(event.getThrowableProxy().getMessage()).contains(credential)));
            assertThat(leaked).isFalse();
        } finally {
            rootLogger.detachAppender(appender);
        }
    }

    // ------------------------------------------------------------------
    // Heartbeat
    // ------------------------------------------------------------------

    @Test
    void heartbeat_sentPeriodicallyWithEnvelope() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId).heartbeat(Duration.ofMillis(150)));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        String frame = server.awaitTextFrame(5);
        assertThat(frame).isNotNull();
        assertThat(frame).contains("\"type\":\"HEARTBEAT\"");
        assertThat(frame).contains(nodeId.toString());
        assertThat(frame).contains("\"messageId\"");
        assertThat(frame).contains("\"timestamp\"");
    }

    @Test
    void disconnect_stopsHeartbeatAndClosesConnection() throws Exception {
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl()).heartbeat(Duration.ofMillis(100)));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);
        assertThat(server.awaitTextFrame(5)).isNotNull();

        connection.disconnect();

        assertThat(connection.isConnected()).isFalse();
        assertThat(connection.currentState()).isEqualTo(NodeConnectionState.DISCONNECTED);

        // Drain any frame already in flight, then assert no further heartbeats arrive.
        server.awaitTextFrame(1);
        assertThat(server.awaitTextFrame(1)).isNull();
    }

    // ------------------------------------------------------------------
    // Reconnect
    // ------------------------------------------------------------------

    @Test
    void reconnect_afterConnectionDropped_reconnectsAndResumesHeartbeat() throws Exception {
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl()).heartbeat(Duration.ofMillis(200))
                .reconnect(Duration.ofMillis(50), Duration.ofMillis(200)));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);
        Map<String, String> firstHandshake = server.awaitHandshakeHeaders(5);
        assertThat(firstHandshake).isNotNull();

        // Drain the pre-drop heartbeat so the assertions below only see the reconnect's own
        // handshake and heartbeat.
        server.awaitTextFrame(1);
        server.dropCurrentConnection();

        awaitState(NodeConnectionState.CONNECTED);
        Map<String, String> reconnectHandshake = server.awaitHandshakeHeaders(5);
        assertThat(reconnectHandshake).isNotNull();
        // Same identity on reconnect - never re-registers.
        assertThat(reconnectHandshake.get("X-Infra-Node-Id")).isEqualTo(firstHandshake.get("X-Infra-Node-Id"));
        assertThat(reconnectHandshake.get("X-Infra-Node-Credential")).isEqualTo(firstHandshake.get("X-Infra-Node-Credential"));
        assertThat(server.awaitTextFrame(5)).contains("\"type\":\"HEARTBEAT\"");
        // A successful connect resets the backoff.
        assertThat(connection.reconnectAttempts()).isZero();
    }

    @Test
    void consoleUnavailable_doesNotThrowAndSchedulesReconnect() throws Exception {
        connection = newConnection(options("http://127.0.0.1:" + allocateClosedPort())
                .reconnect(Duration.ofMillis(20), Duration.ofMillis(100)));

        connection.connect();

        awaitState(NodeConnectionState.RECONNECTING);
        assertThat(connection.isConnected()).isFalse();
    }

    @Test
    void concurrentDisconnectEvents_scheduleOnlyOneReconnect() throws Exception {
        // onClose, onError and a send failure can all report the same loss at once.
        connection = newConnection(options("http://127.0.0.1:" + allocateClosedPort())
                .reconnect(Duration.ofSeconds(5), Duration.ofSeconds(10)));

        int threads = 16;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread thread = new Thread(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                connection.handleDisconnected(null);
            });
            workers.add(thread);
            thread.start();
        }
        for (Thread worker : workers) {
            worker.join(5_000);
        }

        assertThat(connection.currentState()).isEqualTo(NodeConnectionState.RECONNECTING);
        assertThat(connection.reconnectAttempts()).isEqualTo(1);
    }

    @Test
    void disconnectEventFromReplacedConnection_doesNotTearDownCurrentOne() throws Exception {
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl()).heartbeat(Duration.ofMillis(100)));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        // A late onClose/onError from an old socket (connection A) arriving after connection B is up.
        WebSocket staleSocket = (WebSocket) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WebSocket.class}, (proxy, method, args) -> null);
        connection.handleDisconnected(staleSocket);

        assertThat(connection.currentState()).isEqualTo(NodeConnectionState.CONNECTED);
        assertThat(connection.reconnectAttempts()).isZero();
        assertThat(awaitFrameContaining("\"type\":\"HEARTBEAT\"")).isNotNull();
    }

    // ------------------------------------------------------------------
    // Shutdown
    // ------------------------------------------------------------------

    @Test
    void shutdown_cancelsPendingReconnectAndPreventsFurtherConnects() throws Exception {
        connection = newConnection(options("http://127.0.0.1:" + allocateClosedPort())
                .reconnect(Duration.ofMillis(300), Duration.ofSeconds(5)));

        connection.start();
        awaitState(NodeConnectionState.RECONNECTING);

        connection.stop();

        assertThat(connection.isRunning()).isFalse();
        assertThat(connection.isConnected()).isFalse();
        assertThat(connection.currentState()).isEqualTo(NodeConnectionState.DISCONNECTED);

        // No reconnect resurrects the connection.
        Thread.sleep(500);
        assertThat(connection.isConnected()).isFalse();
        assertThat(connection.currentState()).isEqualTo(NodeConnectionState.DISCONNECTED);
    }

    @Test
    void shutdownDuringHandshake_lateOnOpenDoesNotResurrectConnection() throws Exception {
        server = new FakeConsoleServer();
        server.holdHandshakes();
        connection = newConnection(options(server.baseUrl()).heartbeat(Duration.ofMillis(100)));

        connection.start();
        assertThat(server.awaitHandshakeHeaders(5)).isNotNull(); // handshake is now in flight

        connection.stop();
        server.releaseHandshakes(); // handshake succeeds after shutdown began -> onOpen

        // The late onOpen closes the socket instead of reviving the connection.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (server.closeFramesReceived() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(server.closeFramesReceived()).isEqualTo(1);
        assertThat(connection.isConnected()).isFalse();
        assertThat(connection.currentState()).isEqualTo(NodeConnectionState.DISCONNECTED);
        assertThat(server.awaitTextFrame(1)).isNull(); // no heartbeat either
    }

    // ------------------------------------------------------------------
    // Send / receive
    // ------------------------------------------------------------------

    @Test
    void send_deliversArbitraryEnvelope() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        connection.send(new NodeEnvelope<>("m-1", NodeMessageType.RESPONSE, nodeId, Instant.now(), "req-9",
                new EchoResponse("payload")));

        String frame = awaitFrameContaining("\"messageId\":\"m-1\"");
        assertThat(frame).contains("\"requestId\":\"req-9\"").contains("\"echoed\":\"payload\"");
    }

    @Test
    void send_whileDisconnected_isNoOp() {
        connection = newConnection(options("http://127.0.0.1:1"));

        connection.send(new NodeEnvelope<>("m-1", NodeMessageType.HEARTBEAT, UUID.randomUUID(), Instant.now(), null, null));

        assertThat(connection.isConnected()).isFalse();
    }

    @Test
    void request_isHandledAndRespondedWithMatchingRequestId() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId)
                .requestHandler(NodeRequestHandler.of(EchoRequest.class, request -> new EchoResponse("hello " + request.text()))));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        server.sendTextFrame(requestFrame(nodeId, "req-1", "world"));

        String response = awaitFrameContaining("\"type\":\"RESPONSE\"");
        assertThat(response).contains("\"requestId\":\"req-1\"");
        assertThat(response).contains("hello world");
        assertThat(response).contains(nodeId.toString());
    }

    @Test
    void request_withoutHandlerConfigured_respondsWithError() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        server.sendTextFrame(requestFrame(nodeId, "req-1", "world"));

        String response = awaitFrameContaining("\"type\":\"ERROR\"");
        assertThat(response).contains("\"requestId\":\"req-1\"").contains("\"code\":\"UNSUPPORTED_REQUEST\"");
    }

    @Test
    void request_whenHandlerThrows_respondsWithErrorCarryingMessage() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId)
                .requestHandler(NodeRequestHandler.of(EchoRequest.class, request -> {
                    throw new IllegalStateException("handler blew up");
                })));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        server.sendTextFrame(requestFrame(nodeId, "req-1", "world"));

        String response = awaitFrameContaining("\"type\":\"ERROR\"");
        assertThat(response).contains("\"requestId\":\"req-1\"");
        assertThat(response).contains("\"code\":\"REQUEST_FAILED\"").contains("handler blew up");
    }

    @Test
    void request_withMalformedPayload_respondsWithError() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId)
                .requestHandler(NodeRequestHandler.of(EchoRequest.class, request -> new EchoResponse(request.text()))));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        server.sendTextFrame("{\"messageId\":\"m\",\"type\":\"REQUEST\",\"nodeId\":\"" + nodeId
                + "\",\"requestId\":\"req-1\",\"payload\":\"not-an-object\"}");

        String response = awaitFrameContaining("\"type\":\"ERROR\"");
        assertThat(response).contains("\"requestId\":\"req-1\"").contains("\"code\":\"MALFORMED_PAYLOAD\"")
                .contains("Malformed request payload");
    }

    @Test
    void request_doesNotBlockHeartbeat_whileHandlerIsRunning() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        CountDownLatch releaseHandler = new CountDownLatch(1);
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId).heartbeat(Duration.ofMillis(100))
                .requestHandler(NodeRequestHandler.of(EchoRequest.class, request -> {
                    await(releaseHandler);
                    return new EchoResponse("done");
                })));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);
        assertThat(server.awaitTextFrame(5)).isNotNull(); // first heartbeat

        server.sendTextFrame(requestFrame(nodeId, "req-1", "world"));

        // While the handler is still blocked, heartbeats must keep arriving - proving REQUEST
        // handling runs off the WebSocket listener thread.
        assertThat(server.awaitTextFrame(5)).contains("\"type\":\"HEARTBEAT\"");

        releaseHandler.countDown();
        assertThat(awaitFrameContaining("\"type\":\"RESPONSE\"")).contains("\"requestId\":\"req-1\"");
    }

    @Test
    void concurrentRequests_areServedInParallelAndEachCorrelated() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        int requests = 5;
        // Every handler waits until all of them started - only possible if they run concurrently.
        CountDownLatch allStarted = new CountDownLatch(requests);
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId)
                .requestHandler(NodeRequestHandler.of(EchoRequest.class, request -> {
                    allStarted.countDown();
                    await(allStarted);
                    return new EchoResponse("echo-" + request.text());
                })));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        for (int i = 0; i < requests; i++) {
            server.sendTextFrame(requestFrame(nodeId, "req-" + i, "m" + i));
        }

        Set<String> seen = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < requests; i++) {
            String response = awaitFrameContaining("\"type\":\"RESPONSE\"");
            JsonNode tree = JsonMapper.shared().readTree(response);
            String requestId = tree.path("requestId").asString();
            // RESPONSE for req-N must carry the payload of req-N.
            assertThat(tree.path("payload").path("echoed").asString()).isEqualTo("echo-m" + requestId.substring(4));
            seen.add(requestId);
        }
        assertThat(seen).containsExactlyInAnyOrder("req-0", "req-1", "req-2", "req-3", "req-4");
    }

    @Test
    void staleResponse_isNotSentOverReplacementConnection() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId).heartbeat(Duration.ofSeconds(10))
                .reconnect(Duration.ofMillis(50), Duration.ofMillis(100))
                .requestHandler(NodeRequestHandler.of(EchoRequest.class, request -> {
                    handlerStarted.countDown();
                    await(releaseHandler);
                    return new EchoResponse("late");
                })));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);
        assertThat(server.awaitHandshakeHeaders(5)).isNotNull();

        // REQUEST on connection A, then A drops while the handler is still running.
        server.sendTextFrame(requestFrame(nodeId, "req-A", "x"));
        assertThat(handlerStarted.await(5, TimeUnit.SECONDS)).isTrue();
        server.dropCurrentConnection();

        // Connection B established.
        assertThat(server.awaitHandshakeHeaders(5)).isNotNull();
        awaitState(NodeConnectionState.CONNECTED);

        releaseHandler.countDown();
        assertThat(server.awaitTextFrame(1)).isNull(); // the late RESPONSE for req-A never reaches B
    }

    @Test
    void nonRequestEnvelope_isDispatchedToMessageHandlers() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        BlockingQueue<NodeEnvelope<JsonNode>> first = new LinkedBlockingQueue<>();
        BlockingQueue<NodeEnvelope<JsonNode>> second = new LinkedBlockingQueue<>();
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId)
                .messageHandlers(List.of(first::add, second::add)));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        server.sendTextFrame("{\"messageId\":\"ack-1\",\"type\":\"HEARTBEAT_ACK\",\"nodeId\":\"" + nodeId
                + "\",\"payload\":{\"note\":\"hi\"}}");

        NodeEnvelope<JsonNode> received = first.poll(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.type()).isEqualTo(NodeMessageType.HEARTBEAT_ACK);
        assertThat(received.messageId()).isEqualTo("ack-1");
        assertThat(received.payload().path("note").asString()).isEqualTo("hi");
        assertThat(second.poll(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void manyInboundMessages_eachReachHandler() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        Set<String> received = ConcurrentHashMap.newKeySet();
        CountDownLatch all = new CountDownLatch(20);
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId)
                .messageHandlers(List.of(envelope -> {
                    received.add(envelope.messageId());
                    all.countDown();
                })));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        for (int i = 0; i < 20; i++) {
            server.sendTextFrame("{\"messageId\":\"m-" + i + "\",\"type\":\"CONNECT_ACK\",\"nodeId\":\"" + nodeId + "\"}");
        }

        assertThat(all.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(20);
    }

    @Test
    void failingMessageHandler_doesNotBreakConnection() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId).heartbeat(Duration.ofMillis(100))
                .messageHandlers(List.of(envelope -> {
                    throw new IllegalStateException("boom");
                })));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        server.sendTextFrame("{\"messageId\":\"m\",\"type\":\"CONNECT_ACK\",\"nodeId\":\"" + nodeId + "\"}");
        server.sendTextFrame("not json at all");

        assertThat(awaitFrameContaining("\"type\":\"HEARTBEAT\"")).isNotNull();
        assertThat(connection.isConnected()).isTrue();
    }

    // ------------------------------------------------------------------
    // HEALTH reporting
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Streaming / cancel - transported, never interpreted, by the SDK
    // ------------------------------------------------------------------

    @Test
    void streamRequest_reachesMessageHandlers_andStreamMessagesAreSentWithSameRequestId() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        AtomicInteger requestHandlerCalls = new AtomicInteger();
        // A Worker-like handler: serves STREAM_REQUEST by sending chunks, then STREAM_COMPLETE.
        NodeMessageHandler streamingWorker = envelope -> {
            if (envelope.type() != NodeMessageType.STREAM_REQUEST) {
                return;
            }
            EchoRequest request = JsonMapper.shared().treeToValue(envelope.payload(), EchoRequest.class);
            String[] parts = request.text().split(" ");
            for (int i = 0; i < parts.length; i++) {
                connection.send(NodeEnvelope.create(NodeMessageType.STREAM_CHUNK, nodeId, envelope.requestId(),
                        new NodeStreamChunk<>(i, new EchoResponse(parts[i]))));
            }
            connection.send(NodeEnvelope.create(NodeMessageType.STREAM_COMPLETE, nodeId, envelope.requestId(),
                    new NodeStreamComplete(parts.length)));
        };
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId)
                .requestHandler(NodeRequestHandler.of(EchoRequest.class, request -> {
                    requestHandlerCalls.incrementAndGet();
                    return new EchoResponse(request.text());
                }))
                .messageHandlers(List.of(streamingWorker)));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        server.sendTextFrame(JsonMapper.shared().writeValueAsString(NodeEnvelope.create(
                NodeMessageType.STREAM_REQUEST, nodeId, "req-s", new EchoRequest("a b c"))));

        List<JsonNode> chunks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            chunks.add(JsonMapper.shared().readTree(awaitFrameContaining("\"type\":\"STREAM_CHUNK\"")));
        }
        JsonNode complete = JsonMapper.shared().readTree(awaitFrameContaining("\"type\":\"STREAM_COMPLETE\""));

        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.get("requestId").asString()).isEqualTo("req-s"));
        assertThat(chunks.stream().map(chunk -> chunk.get("payload").get("sequence").asLong()).toList())
                .containsExactly(0L, 1L, 2L);
        assertThat(chunks.stream().map(chunk -> chunk.get("payload").get("data").get("echoed").asString()).toList())
                .containsExactly("a", "b", "c");
        assertThat(chunks.stream().map(chunk -> chunk.get("messageId").asString()).distinct().count()).isEqualTo(3);
        assertThat(complete.get("requestId").asString()).isEqualTo("req-s");
        assertThat(complete.get("payload").get("chunkCount").asLong()).isEqualTo(3);
        // STREAM_REQUEST is not a REQUEST: the non-streaming handler is untouched and no RESPONSE is sent.
        assertThat(requestHandlerCalls.get()).isZero();
    }

    @Test
    void streamError_isSentWithRequestIdAndErrorPayload() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        connection.send(NodeEnvelope.create(NodeMessageType.STREAM_ERROR, nodeId, "req-s",
                new NodeError("RUNTIME_UNAVAILABLE", "Model runtime is not reachable")));

        JsonNode frame = JsonMapper.shared().readTree(awaitFrameContaining("\"type\":\"STREAM_ERROR\""));
        assertThat(frame.get("requestId").asString()).isEqualTo("req-s");
        assertThat(frame.get("payload").get("code").asString()).isEqualTo("RUNTIME_UNAVAILABLE");
        assertThat(frame.get("payload").get("message").asString()).isEqualTo("Model runtime is not reachable");
    }

    @Test
    void cancel_reachesMessageHandlersWithTargetRequestId() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        BlockingQueue<NodeEnvelope<JsonNode>> received = new LinkedBlockingQueue<>();
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId).messageHandlers(List.of(received::add)));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        server.sendTextFrame(JsonMapper.shared().writeValueAsString(
                NodeEnvelope.create(NodeMessageType.CANCEL, nodeId, "req-s", null)));

        NodeEnvelope<JsonNode> cancel = received.poll(5, TimeUnit.SECONDS);
        assertThat(cancel).isNotNull();
        assertThat(cancel.type()).isEqualTo(NodeMessageType.CANCEL);
        assertThat(cancel.requestId()).isEqualTo("req-s");
    }

    // ------------------------------------------------------------------
    // ActiveRequestCounter - Worker inference served over OUTBOUND
    // ------------------------------------------------------------------

    @Test
    void activeRequests_countWorkerRequestOnlyWhileHandlerRuns() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        ActiveRequestCounter counter = new ActiveRequestCounter();
        CountDownLatch releaseHandler = new CountDownLatch(1);
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId).counter(counter)
                .requestHandler(NodeRequestHandler.of(WorkerRequest.class, request -> {
                    await(releaseHandler);
                    return new EchoResponse("done");
                })));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);
        assertThat(counter.get()).isZero();

        server.sendTextFrame(workerFrame(NodeMessageType.REQUEST, nodeId, "req-1", "a"));
        awaitValue(counter::get, 1);

        releaseHandler.countDown();
        // The handler has returned by the time its RESPONSE is on the wire.
        assertThat(awaitFrameContaining("\"type\":\"RESPONSE\"")).contains("\"requestId\":\"req-1\"");
        assertThat(counter.get()).isZero();
    }

    @Test
    void activeRequests_decrementedWhenWorkerHandlerThrows() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        ActiveRequestCounter counter = new ActiveRequestCounter();
        AtomicInteger seenByHandler = new AtomicInteger(-1);
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId).counter(counter)
                .requestHandler(NodeRequestHandler.of(WorkerRequest.class, request -> {
                    seenByHandler.set(counter.get());
                    throw new IllegalStateException("inference failed");
                })));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        server.sendTextFrame(workerFrame(NodeMessageType.REQUEST, nodeId, "req-1", "a"));

        assertThat(awaitFrameContaining("\"type\":\"ERROR\"")).contains("\"code\":\"REQUEST_FAILED\"");
        assertThat(seenByHandler.get()).isEqualTo(1);
        assertThat(counter.get()).isZero();
    }

    @Test
    void activeRequests_trackConcurrentWorkerRequests() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        ActiveRequestCounter counter = new ActiveRequestCounter();
        Map<String, CountDownLatch> release = Map.of("A", new CountDownLatch(1), "B", new CountDownLatch(1));
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId).counter(counter)
                .requestHandler(NodeRequestHandler.of(WorkerRequest.class, request -> {
                    String name = request.messages().get(0).content();
                    await(release.get(name));
                    return new EchoResponse(name);
                })));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        server.sendTextFrame(workerFrame(NodeMessageType.REQUEST, nodeId, "req-A", "A"));
        awaitValue(counter::get, 1);
        server.sendTextFrame(workerFrame(NodeMessageType.REQUEST, nodeId, "req-B", "B"));
        awaitValue(counter::get, 2);

        release.get("A").countDown();
        assertThat(awaitFrameContaining("\"type\":\"RESPONSE\"")).contains("\"requestId\":\"req-A\"");
        assertThat(counter.get()).isEqualTo(1);

        release.get("B").countDown();
        assertThat(awaitFrameContaining("\"type\":\"RESPONSE\"")).contains("\"requestId\":\"req-B\"");
        assertThat(counter.get()).isZero();
    }

    @Test
    void activeRequests_notCountedForNonWorkerHandler() throws Exception {
        // A Router serves RoutingRequest through the same NodeRequestHandler SPI; it runs no inference.
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        ActiveRequestCounter counter = new ActiveRequestCounter();
        AtomicInteger seenByHandler = new AtomicInteger(-1);
        AtomicInteger seenByStream = new AtomicInteger(-1);
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId).counter(counter)
                .requestHandler(NodeRequestHandler.of(EchoRequest.class, request -> {
                    seenByHandler.set(counter.get());
                    return new EchoResponse(request.text());
                }))
                .streamRequestHandler(NodeStreamRequestHandler.of(EchoRequest.class, request -> {
                    seenByStream.set(counter.get());
                    return Flux.just(new EchoResponse(request.text()));
                })));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        server.sendTextFrame(requestFrame(nodeId, "req-1", "route me"));
        assertThat(awaitFrameContaining("\"type\":\"RESPONSE\"")).contains("route me");
        server.sendTextFrame(JsonMapper.shared().writeValueAsString(NodeEnvelope.create(
                NodeMessageType.STREAM_REQUEST, nodeId, "req-s", new EchoRequest("stream me"))));
        awaitFrameContaining("\"type\":\"STREAM_COMPLETE\"");

        assertThat(seenByHandler.get()).isZero();
        assertThat(seenByStream.get()).isZero();
        assertThat(counter.get()).isZero();
    }

    @Test
    void activeRequests_coverWholeStreamUntilComplete() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        ActiveRequestCounter counter = new ActiveRequestCounter();
        Sinks.Many<EchoResponse> sink = Sinks.many().unicast().onBackpressureBuffer();
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId).counter(counter)
                .streamRequestHandler(NodeStreamRequestHandler.of(WorkerRequest.class, request -> sink.asFlux())));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        server.sendTextFrame(workerFrame(NodeMessageType.STREAM_REQUEST, nodeId, "req-s", "a"));
        awaitValue(counter::get, 1);
        awaitValue(sink::currentSubscriberCount, 1);

        for (int i = 0; i < 3; i++) {
            sink.tryEmitNext(new EchoResponse("chunk-" + i));
            JsonNode chunk = JsonMapper.shared().readTree(awaitFrameContaining("\"type\":\"STREAM_CHUNK\""));
            assertThat(chunk.get("requestId").asString()).isEqualTo("req-s");
            assertThat(chunk.get("payload").get("sequence").asLong()).isEqualTo(i);
            assertThat(chunk.get("payload").get("data").get("echoed").asString()).isEqualTo("chunk-" + i);
            assertThat(counter.get()).isEqualTo(1);
        }

        sink.tryEmitComplete();
        JsonNode complete = JsonMapper.shared().readTree(awaitFrameContaining("\"type\":\"STREAM_COMPLETE\""));
        assertThat(complete.get("requestId").asString()).isEqualTo("req-s");
        assertThat(complete.get("payload").get("chunkCount").asLong()).isEqualTo(3);
        assertThat(counter.get()).isZero();
    }

    @Test
    void activeRequests_decrementedWhenStreamFails() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        ActiveRequestCounter counter = new ActiveRequestCounter();
        Sinks.Many<EchoResponse> sink = Sinks.many().unicast().onBackpressureBuffer();
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId).counter(counter)
                .streamRequestHandler(NodeStreamRequestHandler.of(WorkerRequest.class, request -> sink.asFlux())));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        server.sendTextFrame(workerFrame(NodeMessageType.STREAM_REQUEST, nodeId, "req-s", "a"));
        awaitValue(counter::get, 1);
        awaitValue(sink::currentSubscriberCount, 1);

        sink.tryEmitError(new IllegalStateException("runtime unreachable"));

        JsonNode error = JsonMapper.shared().readTree(awaitFrameContaining("\"type\":\"STREAM_ERROR\""));
        assertThat(error.get("requestId").asString()).isEqualTo("req-s");
        assertThat(error.get("payload").get("code").asString()).isEqualTo("REQUEST_FAILED");
        assertThat(counter.get()).isZero();
    }

    @Test
    void activeRequests_decrementedWhenStreamHandlerThrows() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        ActiveRequestCounter counter = new ActiveRequestCounter();
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId).counter(counter)
                .streamRequestHandler(NodeStreamRequestHandler.of(WorkerRequest.class, request -> {
                    throw new IllegalStateException("cannot start inference");
                })));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        server.sendTextFrame(workerFrame(NodeMessageType.STREAM_REQUEST, nodeId, "req-s", "a"));

        assertThat(awaitFrameContaining("\"type\":\"STREAM_ERROR\"")).contains("cannot start inference");
        assertThat(counter.get()).isZero();
    }

    @Test
    void activeRequests_decrementedExactlyOnceWhenStreamIsCancelled() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        ActiveRequestCounter counter = new ActiveRequestCounter();
        Sinks.Many<EchoResponse> sink = Sinks.many().unicast().onBackpressureBuffer();
        CountDownLatch upstreamCancelled = new CountDownLatch(1);
        BlockingQueue<NodeEnvelope<JsonNode>> seenByMessageHandler = new LinkedBlockingQueue<>();
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId).counter(counter)
                .streamRequestHandler(NodeStreamRequestHandler.of(WorkerRequest.class,
                        request -> sink.asFlux().doOnCancel(upstreamCancelled::countDown)))
                .messageHandlers(List.of(seenByMessageHandler::add)));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        server.sendTextFrame(workerFrame(NodeMessageType.STREAM_REQUEST, nodeId, "req-s", "a"));
        awaitValue(counter::get, 1);
        awaitValue(sink::currentSubscriberCount, 1);
        sink.tryEmitNext(new EchoResponse("chunk-0"));
        awaitFrameContaining("\"type\":\"STREAM_CHUNK\"");

        String cancel = JsonMapper.shared().writeValueAsString(
                NodeEnvelope.create(NodeMessageType.CANCEL, nodeId, "req-s", null));
        server.sendTextFrame(cancel);

        // The CANCEL reaches the inference itself, and only that ends the active request.
        assertThat(upstreamCancelled.await(5, TimeUnit.SECONDS)).isTrue();
        awaitValue(counter::get, 0);

        // Nothing that follows a finished stream may decrement again: a repeated CANCEL, a CANCEL
        // for an unknown request, a late terminal signal from the source, or losing the connection.
        server.sendTextFrame(cancel);
        server.sendTextFrame(JsonMapper.shared().writeValueAsString(
                NodeEnvelope.create(NodeMessageType.CANCEL, nodeId, "req-unknown", null)));
        for (int i = 0; i < 3; i++) {
            assertThat(seenByMessageHandler.poll(5, TimeUnit.SECONDS)).isNotNull(); // all three CANCELs handled
        }
        sink.tryEmitComplete();
        connection.disconnect();

        assertThat(counter.get()).isZero();
        // A cancelled stream is not answered with STREAM_COMPLETE / STREAM_ERROR.
        for (String frame = server.awaitTextFrame(1); frame != null; frame = server.awaitTextFrame(1)) {
            assertThat(frame).doesNotContain("STREAM_COMPLETE").doesNotContain("STREAM_ERROR");
        }
    }

    @Test
    void activeRequests_decrementedWhenConnectionDropsMidStream() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        ActiveRequestCounter counter = new ActiveRequestCounter();
        Sinks.Many<EchoResponse> sink = Sinks.many().unicast().onBackpressureBuffer();
        CountDownLatch upstreamCancelled = new CountDownLatch(1);
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId).counter(counter)
                .streamRequestHandler(NodeStreamRequestHandler.of(WorkerRequest.class,
                        request -> sink.asFlux().doOnCancel(upstreamCancelled::countDown))));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        server.sendTextFrame(workerFrame(NodeMessageType.STREAM_REQUEST, nodeId, "req-s", "a"));
        awaitValue(counter::get, 1);
        awaitValue(sink::currentSubscriberCount, 1);

        server.dropCurrentConnection();

        assertThat(upstreamCancelled.await(5, TimeUnit.SECONDS)).isTrue();
        awaitValue(counter::get, 0);
    }

    @Test
    void streamRequestHandler_takesStreamRequestsFromMessageHandlers() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        BlockingQueue<NodeEnvelope<JsonNode>> seenByMessageHandler = new LinkedBlockingQueue<>();
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId)
                .streamRequestHandler(NodeStreamRequestHandler.of(WorkerRequest.class,
                        request -> Flux.just(new EchoResponse("only once"))))
                .messageHandlers(List.of(seenByMessageHandler::add)));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        server.sendTextFrame(workerFrame(NodeMessageType.STREAM_REQUEST, nodeId, "req-s", "a"));
        awaitFrameContaining("\"type\":\"STREAM_COMPLETE\"");
        server.sendTextFrame("{\"messageId\":\"ack\",\"type\":\"HEARTBEAT_ACK\",\"nodeId\":\"" + nodeId + "\"}");

        // Served by the SDK alone - a NodeMessageHandler serving it too would stream it twice.
        assertThat(seenByMessageHandler.poll(5, TimeUnit.SECONDS).type()).isEqualTo(NodeMessageType.HEARTBEAT_ACK);
    }

    @Test
    void health_reportsActiveRequestsWhileWorkerRequestIsRunning() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        ActiveRequestCounter counter = new ActiveRequestCounter();
        CountDownLatch releaseHandler = new CountDownLatch(1);
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId).counter(counter)
                .health(Duration.ofMillis(100), () -> new NodeHealthResponse(
                        NodeStatus.UP, SAMPLE_HEALTH.system(), new NodeHealthResponse.RuntimeInfo(counter.get())))
                .requestHandler(NodeRequestHandler.of(WorkerRequest.class, request -> {
                    await(releaseHandler);
                    return new EchoResponse("done");
                })));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);
        assertThat(awaitFrameContaining("\"type\":\"HEALTH\"")).contains("\"activeRequests\":0");

        server.sendTextFrame(workerFrame(NodeMessageType.REQUEST, nodeId, "req-1", "a"));
        assertThat(awaitFrameContaining("\"activeRequests\":1")).contains("\"type\":\"HEALTH\"");

        releaseHandler.countDown();
        awaitFrameContaining("\"type\":\"RESPONSE\"");
        assertThat(awaitFrameContaining("\"type\":\"HEALTH\"")).contains("\"activeRequests\":0");
    }

    @Test
    void health_sentAsHealthEnvelopeCarryingNodeHealthResponse_separateFromHeartbeat() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId)
                .heartbeat(Duration.ofMillis(100)).health(Duration.ofMillis(150), () -> SAMPLE_HEALTH));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        JsonNode health = JsonMapper.shared().readTree(awaitFrameContaining("\"type\":\"HEALTH\""));
        assertThat(health.path("nodeId").asString()).isEqualTo(nodeId.toString());
        assertThat(health.path("messageId").asString()).isNotBlank();
        assertThat(health.path("timestamp").isMissingNode()).isFalse();
        // Exactly NodeHealthResponse - the DIRECT /api/v1/health payload, with no node type added.
        assertThat(JsonMapper.shared().treeToValue(health.path("payload"), NodeHealthResponse.class))
                .isEqualTo(SAMPLE_HEALTH);
        assertThat(health.path("payload").has("nodeType")).isFalse();

        // HEARTBEAT keeps flowing independently and never carries health.
        JsonNode heartbeat = JsonMapper.shared().readTree(awaitFrameContaining("\"type\":\"HEARTBEAT\""));
        assertThat(heartbeat.path("payload").has("status")).isFalse();
    }

    @Test
    void health_notSentWithoutHealthSource() throws Exception {
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl()).heartbeat(Duration.ofMillis(100)));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        for (int i = 0; i < 3; i++) {
            assertThat(server.awaitTextFrame(2)).doesNotContain("\"type\":\"HEALTH\"");
        }
    }

    @Test
    void health_notCollectedOrSentWhileDisconnected() throws Exception {
        AtomicInteger collections = new AtomicInteger();
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl())
                .health(Duration.ofMillis(100), () -> {
                    collections.incrementAndGet();
                    return SAMPLE_HEALTH;
                }));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);
        awaitFrameContaining("\"type\":\"HEALTH\"");

        connection.disconnect();
        server.awaitTextFrame(1); // drain a report that may already be in flight
        int afterDisconnect = collections.get();

        assertThat(server.awaitTextFrame(1)).isNull();
        assertThat(collections.get()).isEqualTo(afterDisconnect);
    }

    @Test
    void health_resumesAfterReconnect() throws Exception {
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl())
                .health(Duration.ofMillis(200), () -> SAMPLE_HEALTH)
                .reconnect(Duration.ofMillis(50), Duration.ofMillis(200)));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);
        assertThat(server.awaitHandshakeHeaders(5)).isNotNull();
        awaitFrameContaining("\"type\":\"HEALTH\"");

        server.dropCurrentConnection();
        assertThat(server.awaitHandshakeHeaders(5)).isNotNull();
        awaitState(NodeConnectionState.CONNECTED);

        assertThat(awaitFrameContaining("\"type\":\"HEALTH\"")).isNotNull();
    }

    @Test
    void stop_shutsDownHealthScheduler() throws Exception {
        AtomicInteger collections = new AtomicInteger();
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl())
                .health(Duration.ofMillis(100), () -> {
                    collections.incrementAndGet();
                    return SAMPLE_HEALTH;
                }));

        connection.start();
        awaitState(NodeConnectionState.CONNECTED);
        awaitFrameContaining("\"type\":\"HEALTH\"");

        connection.stop();
        server.awaitTextFrame(1);
        int afterStop = collections.get();

        Thread.sleep(500);
        assertThat(collections.get()).isEqualTo(afterStop);
        assertThat(server.awaitTextFrame(1)).isNull();
    }

    @Test
    void failingHealthSource_skipsReportButKeepsReporting() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl())
                .health(Duration.ofMillis(100), () -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new IllegalStateException("collector failed");
                    }
                    return SAMPLE_HEALTH;
                }));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        assertThat(awaitFrameContaining("\"type\":\"HEALTH\"")).isNotNull();
        assertThat(calls.get()).isGreaterThanOrEqualTo(2);
        assertThat(connection.isConnected()).isTrue();
    }

    @Test
    void healthAck_isDeliveredToHandlersAndDoesNotChangeConnectionState() throws Exception {
        UUID nodeId = UUID.randomUUID();
        server = new FakeConsoleServer();
        BlockingQueue<NodeEnvelope<JsonNode>> received = new LinkedBlockingQueue<>();
        connection = newConnection(options(server.baseUrl()).nodeId(nodeId)
                .health(Duration.ofMillis(100), () -> SAMPLE_HEALTH)
                .messageHandlers(List.of(received::add)));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);
        String healthMessageId = JsonMapper.shared()
                .readTree(awaitFrameContaining("\"type\":\"HEALTH\"")).path("messageId").asString();

        server.sendTextFrame("{\"messageId\":\"ack-h\",\"type\":\"HEALTH_ACK\",\"nodeId\":\"" + nodeId
                + "\",\"requestId\":\"" + healthMessageId + "\"}");

        NodeEnvelope<JsonNode> ack = received.poll(5, TimeUnit.SECONDS);
        assertThat(ack).isNotNull();
        assertThat(ack.type()).isEqualTo(NodeMessageType.HEALTH_ACK);
        assertThat(connection.isConnected()).isTrue();
        // Reporting carries on unaffected by the ACK.
        assertThat(awaitFrameContaining("\"type\":\"HEALTH\"")).isNotNull();
    }

    @Test
    void credential_neverAppearsInHealthFrames() throws Exception {
        String credential = "health-secret-" + UUID.randomUUID();
        server = new FakeConsoleServer();
        connection = newConnection(options(server.baseUrl()).credential(credential)
                .health(Duration.ofMillis(100), () -> SAMPLE_HEALTH));

        connection.connect();
        awaitState(NodeConnectionState.CONNECTED);

        assertThat(awaitFrameContaining("\"type\":\"HEALTH\"")).doesNotContain(credential);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static final NodeHealthResponse SAMPLE_HEALTH = new NodeHealthResponse(
            NodeStatus.UP,
            new NodeHealthResponse.SystemInfo(
                    new NodeHealthResponse.CpuInfo("test-cpu", 12.5),
                    new NodeHealthResponse.MemoryInfo(1000L, 400L, 600L),
                    List.of()),
            new NodeHealthResponse.RuntimeInfo(3));

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String requestFrame(UUID nodeId, String requestId, String text) {
        NodeEnvelope<EchoRequest> envelope = new NodeEnvelope<>(
                UUID.randomUUID().toString(), NodeMessageType.REQUEST, nodeId, Instant.now(), requestId, new EchoRequest(text));
        return JsonMapper.shared().writeValueAsString(envelope);
    }

    private String workerFrame(NodeMessageType type, UUID nodeId, String requestId, String content) {
        return JsonMapper.shared().writeValueAsString(NodeEnvelope.create(type, nodeId, requestId,
                new WorkerRequest(List.of(ChatMessage.user(content)), null)));
    }

    private static void awaitValue(IntSupplier actual, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (actual.getAsInt() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for value=" + expected + ", was=" + actual.getAsInt());
            }
            Thread.sleep(10);
        }
    }

    // Heartbeats keep arriving on the same connection, so scan frames until one matches rather
    // than assuming the very next frame is the one we're waiting for.
    private String awaitFrameContaining(String needle) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            String frame = server.awaitTextFrame(1);
            if (frame != null && frame.contains(needle)) {
                return frame;
            }
        }
        throw new AssertionError("Timed out waiting for a frame containing: " + needle);
    }

    private void awaitState(NodeConnectionState expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (connection.currentState() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "Timed out waiting for state=" + expected + ", was=" + connection.currentState());
            }
            Thread.sleep(20);
        }
    }

    private int allocateClosedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Options options(String consoleUrl) {
        return new Options(consoleUrl);
    }

    private WebSocketOutboundNodeConnection newConnection(Options options) {
        NodeConnectionProperties properties = new NodeConnectionProperties();
        properties.setConnectionMode(NodeConnectionMode.OUTBOUND);
        properties.setConsoleUrl(options.consoleUrl);
        properties.setNodeId(options.nodeId);
        properties.setCredential(options.credential);
        properties.getOutbound().setHeartbeatInterval(options.heartbeat);
        properties.getOutbound().setHealthInterval(options.healthInterval);
        properties.getOutbound().getReconnect().setInitialDelay(options.reconnectInitial);
        properties.getOutbound().getReconnect().setMaxDelay(options.reconnectMax);
        return new WebSocketOutboundNodeConnection(
                properties, options.requestHandler, options.streamRequestHandler, options.messageHandlers,
                options.healthSource, options.counter);
    }

    private static final class Options {
        private final String consoleUrl;
        private UUID nodeId = UUID.randomUUID();
        private String credential = "credential";
        private Duration heartbeat = Duration.ofSeconds(10);
        private Duration reconnectInitial = Duration.ofMillis(50);
        private Duration reconnectMax = Duration.ofMillis(200);
        private NodeRequestHandler<?, ?> requestHandler;
        private List<NodeMessageHandler> messageHandlers = List.of();
        private Duration healthInterval = Duration.ofSeconds(30);
        private Supplier<NodeHealthResponse> healthSource;
        private NodeStreamRequestHandler<?, ?> streamRequestHandler;
        private ActiveRequestCounter counter;

        private Options(String consoleUrl) {
            this.consoleUrl = consoleUrl;
        }

        Options nodeId(UUID value) {
            nodeId = value;
            return this;
        }

        Options credential(String value) {
            credential = value;
            return this;
        }

        Options heartbeat(Duration value) {
            heartbeat = value;
            return this;
        }

        Options reconnect(Duration initial, Duration max) {
            reconnectInitial = initial;
            reconnectMax = max;
            return this;
        }

        Options requestHandler(NodeRequestHandler<?, ?> value) {
            requestHandler = value;
            return this;
        }

        Options streamRequestHandler(NodeStreamRequestHandler<?, ?> value) {
            streamRequestHandler = value;
            return this;
        }

        Options counter(ActiveRequestCounter value) {
            counter = value;
            return this;
        }

        Options messageHandlers(List<NodeMessageHandler> value) {
            messageHandlers = value;
            return this;
        }

        Options health(Duration interval, Supplier<NodeHealthResponse> source) {
            healthInterval = interval;
            healthSource = source;
            return this;
        }
    }
}
