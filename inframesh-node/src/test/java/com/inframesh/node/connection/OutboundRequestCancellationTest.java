package com.inframesh.node.connection;

import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.connection.NodeEnvelope;
import com.inframesh.node.dto.connection.NodeError;
import com.inframesh.node.dto.worker.WorkerRequest;
import com.inframesh.node.enums.NodeConnectionMode;
import com.inframesh.node.enums.NodeConnectionState;
import com.inframesh.node.enums.NodeMessageType;
import com.inframesh.node.monitor.ActiveRequestCounter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-streaming REQUEST cancellation (CANCEL with the REQUEST's requestId) against a real socket
 * ({@link FakeConsoleServer}). The request ends - leaves the registry and the active count, and sends
 * its single final message - only when the handler call has actually returned (or never started).
 */
class OutboundRequestCancellationTest {

    private static final JsonMapper JSON = JsonMapper.shared();

    private FakeConsoleServer server;
    private WebSocketOutboundNodeConnection connection;
    private final ActiveRequestCounter counter = new ActiveRequestCounter();
    private final UUID nodeId = UUID.randomUUID();

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.stop();
        }
        if (server != null) {
            server.close();
        }
    }

    // ------------------------------------------------------------------ CANCEL while running

    @Test
    void cancelWhileRunning_interruptsHandler_thenSendsCancelledErrorOnce() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        connect(NodeRequestHandler.of(WorkerRequest.class, request -> {
            started.countDown();
            try {
                new CountDownLatch(1).await(); // blocks until interrupted
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw new IllegalStateException("interrupted");
            }
            return "never";
        }));

        server.sendTextFrame(workerFrame(NodeMessageType.REQUEST, "req-1"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        server.sendTextFrame(cancelFrame("req-1"));

        JsonNode error = awaitFinal("req-1");
        assertThat(error.get("type").asString()).isEqualTo("ERROR");
        assertThat(error.at("/payload/code").asString()).isEqualTo(NodeError.CANCELLED);
        assertThat(interrupted).isTrue();
        assertThat(counter.get()).isZero();
        assertThat(connection.activeRequestEntries()).isZero();
        assertNoFinalWithin("req-1", 300); // nothing more for req-1 (no RESPONSE, no second ERROR)
    }

    // §5.3: the request is not ended by the cancel itself, only by the handler returning.
    @Test
    void cancelOfHandlerIgnoringInterrupt_staysActiveUntilHandlerReturns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch sawInterrupt = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        connect(NodeRequestHandler.of(WorkerRequest.class, request -> {
            started.countDown();
            while (true) {
                try {
                    if (release.await(10, TimeUnit.SECONDS)) {
                        return "finished anyway";
                    }
                } catch (InterruptedException e) {
                    sawInterrupt.countDown(); // ignores the interrupt and keeps working
                }
            }
        }));

        server.sendTextFrame(workerFrame(NodeMessageType.REQUEST, "req-1"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.get()).isEqualTo(1);

        server.sendTextFrame(cancelFrame("req-1"));
        assertThat(sawInterrupt.await(5, TimeUnit.SECONDS)).as("interrupt delivered to the handler thread").isTrue();

        // The cancel was requested and delivered, but the handler is still running: still tracked and counted, no final message.
        assertThat(counter.get()).isEqualTo(1);
        assertThat(connection.activeRequestEntries()).isEqualTo(1);
        assertNoFinalWithin("req-1", 500);

        release.countDown();
        JsonNode error = awaitFinal("req-1");
        assertThat(error.get("type").asString()).isEqualTo("ERROR");
        assertThat(error.at("/payload/code").asString()).isEqualTo(NodeError.CANCELLED);
        assertThat(counter.get()).isZero();
        assertThat(connection.activeRequestEntries()).isZero();
        assertNoFinalWithin("req-1", 300); // the handler's result is never sent as a RESPONSE
    }

    @Test
    void reactorBlockHandler_isInterruptedAndCancelsItsUpstream() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch upstreamCancelled = new CountDownLatch(1);
        connect(NodeRequestHandler.of(WorkerRequest.class, request -> Mono.<String>never()
                .doOnSubscribe(s -> subscribed.countDown())
                .doOnCancel(upstreamCancelled::countDown)
                .block()));

        server.sendTextFrame(workerFrame(NodeMessageType.REQUEST, "req-1"));
        assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
        server.sendTextFrame(cancelFrame("req-1"));

        assertThat(awaitFinal("req-1").at("/payload/code").asString()).isEqualTo(NodeError.CANCELLED);
        assertThat(upstreamCancelled.await(5, TimeUnit.SECONDS)).as("block() disposed its subscription").isTrue();
        assertThat(counter.get()).isZero();
    }

    @Test
    void duplicateCancel_endsTheRequestOnce() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupts = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger interruptCount = new AtomicInteger();
        connect(NodeRequestHandler.of(WorkerRequest.class, request -> {
            started.countDown();
            while (true) {
                try {
                    if (release.await(10, TimeUnit.SECONDS)) {
                        return "x";
                    }
                } catch (InterruptedException e) {
                    interruptCount.incrementAndGet();
                    interrupts.countDown();
                }
            }
        }));

        server.sendTextFrame(workerFrame(NodeMessageType.REQUEST, "req-1"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        server.sendTextFrame(cancelFrame("req-1"));
        server.sendTextFrame(cancelFrame("req-1"));
        server.sendTextFrame(cancelFrame("req-1"));
        assertThat(interrupts.await(500, TimeUnit.MILLISECONDS)).as("only the first CANCEL interrupts").isFalse();
        assertThat(interruptCount.get()).isEqualTo(1);

        release.countDown();
        assertThat(awaitFinal("req-1").at("/payload/code").asString()).isEqualTo(NodeError.CANCELLED);
        assertNoFinalWithin("req-1", 300);
        assertThat(counter.get()).isZero();
    }

    @Test
    void cancelForUnknownOrCompletedRequest_isNoOp_andStillReachesMessageHandlers() throws Exception {
        List<String> cancelsSeen = new CopyOnWriteArrayList<>();
        connect(NodeRequestHandler.of(WorkerRequest.class, request -> "done"),
                List.of(envelope -> {
                    if (envelope.type() == NodeMessageType.CANCEL) {
                        cancelsSeen.add(envelope.requestId());
                    }
                }));

        server.sendTextFrame(workerFrame(NodeMessageType.REQUEST, "req-1"));
        assertThat(awaitFinal("req-1").get("type").asString()).isEqualTo("RESPONSE");

        server.sendTextFrame(cancelFrame("req-1"));       // already completed
        server.sendTextFrame(cancelFrame("req-unknown")); // never existed
        awaitValue(cancelsSeen::size, 2);
        assertNoFinalWithin("req-1", 300);
        assertNoFinalWithin("req-unknown", 0);
        assertThat(counter.get()).isZero();
        assertThat(connection.activeRequestEntries()).isZero();
    }

    // ------------------------------------------------------------------ CANCEL before the handler starts

    @Test
    void cancelBeforeStart_neverRunsHandler_andSendsCancelledError() throws Exception {
        AtomicInteger handlerCalls = new AtomicInteger();
        NodeRequestHandler<WorkerRequest, String> handler = NodeRequestHandler.of(WorkerRequest.class, request -> {
            handlerCalls.incrementAndGet();
            return "ran";
        });
        connect(handler);
        List<Runnable> queued = new ArrayList<>();
        OutboundRequestRegistry registry = new OutboundRequestRegistry(connection, handler, queued::add, counter);
        WebSocket ws = currentWebSocket();

        registry.start(ws, parsedWorkerEnvelope("req-q"));
        assertThat(registry.size()).isEqualTo(1);
        registry.cancel(ws, "req-q");

        assertThat(awaitFinal("req-q").at("/payload/code").asString()).isEqualTo(NodeError.CANCELLED);
        assertThat(registry.size()).isZero();

        queued.forEach(Runnable::run); // the executor finally gets to it
        assertThat(handlerCalls).hasValue(0);
        assertThat(counter.get()).isZero();
        assertNoFinalWithin("req-q", 300);
    }

    @Test
    void executorRejection_cleansUpAndReportsFailure() throws Exception {
        NodeRequestHandler<WorkerRequest, String> handler = NodeRequestHandler.of(WorkerRequest.class, request -> "ran");
        connect(handler);
        Executor rejecting = task -> {
            throw new RejectedExecutionException("shut down");
        };
        OutboundRequestRegistry registry = new OutboundRequestRegistry(connection, handler, rejecting, counter);

        registry.start(currentWebSocket(), parsedWorkerEnvelope("req-r"));

        JsonNode error = awaitFinal("req-r");
        assertThat(error.get("type").asString()).isEqualTo("ERROR");
        assertThat(error.at("/payload/code").asString()).isEqualTo("REQUEST_FAILED");
        assertThat(registry.size()).isZero();
        assertThat(counter.get()).isZero();
    }

    // ------------------------------------------------------------------ races

    @Test
    void completionRacingCancel_endsEachRequestExactlyOnce() throws Exception {
        assertExactlyOnceUnderCancelRace(NodeRequestHandler.of(WorkerRequest.class, request -> "done"), "RESPONSE");
    }

    @Test
    void failureRacingCancel_endsEachRequestExactlyOnce() throws Exception {
        assertExactlyOnceUnderCancelRace(NodeRequestHandler.of(WorkerRequest.class, request -> {
            throw new IllegalStateException("boom");
        }), "ERROR");
    }

    private void assertExactlyOnceUnderCancelRace(NodeRequestHandler<?, ?> handler, String uncancelledType) throws Exception {
        connect(handler);
        int n = 50;
        for (int i = 0; i < n; i++) {
            // REQUEST immediately followed by its CANCEL - either may win.
            server.sendTextFrame(workerFrame(NodeMessageType.REQUEST, "race-" + i));
            server.sendTextFrame(cancelFrame("race-" + i));
        }

        Map<String, List<String>> finals = collectFinals(n, Duration.ofSeconds(10));
        merge(finals, collectFinals(0, Duration.ofMillis(300))); // nothing more may follow
        assertThat(finals).hasSize(n);
        finals.forEach((requestId, kinds) -> {
            assertThat(kinds).as(requestId).hasSize(1);
            assertThat(kinds.get(0)).as(requestId).isIn(uncancelledType + ":" + codeOf(uncancelledType), "ERROR:CANCELLED");
        });
        awaitValue(counter::get, 0);
        awaitValue(connection::activeRequestEntries, 0);
    }

    private static String codeOf(String type) {
        return type.equals("RESPONSE") ? "-" : "REQUEST_FAILED";
    }

    // ------------------------------------------------------------------ connection loss / reconnect

    @Test
    void connectionLoss_cancelsRunningRequest_finalMessageNeverReachesNewConnection() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch sawInterrupt = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        connect(NodeRequestHandler.of(WorkerRequest.class, request -> {
            started.countDown();
            while (true) {
                try {
                    if (release.await(10, TimeUnit.SECONDS)) {
                        return "late";
                    }
                } catch (InterruptedException e) {
                    sawInterrupt.countDown();
                }
            }
        }));
        assertThat(server.awaitHandshakeHeaders(5)).isNotNull();

        server.sendTextFrame(workerFrame(NodeMessageType.REQUEST, "req-A"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        server.dropCurrentConnection();
        assertThat(sawInterrupt.await(5, TimeUnit.SECONDS)).as("connection loss cancels the running request").isTrue();

        assertThat(server.awaitHandshakeHeaders(5)).isNotNull(); // reconnected
        awaitState(NodeConnectionState.CONNECTED);
        // Still running: still tracked and counted.
        assertThat(counter.get()).isEqualTo(1);
        assertThat(connection.activeRequestEntries()).isEqualTo(1);

        release.countDown();
        awaitValue(counter::get, 0);
        awaitValue(connection::activeRequestEntries, 0);
        assertNoFinalWithin("req-A", 500); // neither RESPONSE nor ERROR(CANCELLED) over the new connection
    }

    @Test
    void sameRequestIdOnOldAndNewConnection_areTrackedSeparately() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger interrupted = new AtomicInteger();
        NodeRequestHandler<WorkerRequest, String> handler = NodeRequestHandler.of(WorkerRequest.class, request -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
            }
            return "x";
        });
        connect(handler);
        Executor ownThreads = task -> new Thread(task).start();
        OutboundRequestRegistry registry = new OutboundRequestRegistry(connection, handler, ownThreads, counter);
        WebSocket oldWs = fakeWebSocket();
        WebSocket newWs = fakeWebSocket();

        registry.start(oldWs, parsedWorkerEnvelope("same-id"));
        registry.start(newWs, parsedWorkerEnvelope("same-id"));
        awaitValue(counter::get, 2);
        assertThat(registry.size()).isEqualTo(2);

        registry.cancelAll(oldWs, "old connection lost");
        awaitValue(interrupted::get, 1);
        // the old one has ended; the new one is untouched and still running
        awaitValue(registry::size, 1);
        assertThat(counter.get()).isEqualTo(1);

        release.countDown();
        awaitValue(registry::size, 0);
        assertThat(interrupted.get()).isEqualTo(1);
        assertThat(counter.get()).isZero();
    }

    // ------------------------------------------------------------------ Error / duplicate / shutdown boundaries

    @Test
    void cancelledHandlerEndingWithError_stillSendsCancelled_andErrorPropagates() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        NodeRequestHandler<WorkerRequest, String> handler = NodeRequestHandler.of(WorkerRequest.class, request -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                throw new AssertionError("handler failed with an Error after the interrupt");
            }
            return "never";
        });
        connect(handler);
        java.util.concurrent.atomic.AtomicReference<Throwable> escaped = new java.util.concurrent.atomic.AtomicReference<>();
        OutboundRequestRegistry registry = new OutboundRequestRegistry(connection, handler, capturing(escaped), counter);
        WebSocket ws = currentWebSocket();

        registry.start(ws, parsedWorkerEnvelope("req-e"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        registry.cancel(ws, "req-e");

        assertThat(awaitFinal("req-e").at("/payload/code").asString()).isEqualTo(NodeError.CANCELLED);
        awaitValue(() -> escaped.get() == null ? 0 : 1, 1);
        assertThat(escaped.get()).isInstanceOf(AssertionError.class); // not swallowed
        assertThat(registry.size()).isZero();
        assertThat(counter.get()).isZero();
        assertNoFinalWithin("req-e", 300);
    }

    @Test
    void uncancelledHandlerEndingWithError_sendsNothing_cleansUp_andErrorPropagates() throws Exception {
        NodeRequestHandler<WorkerRequest, String> handler = NodeRequestHandler.of(WorkerRequest.class, request -> {
            throw new AssertionError("fatal");
        });
        connect(handler);
        java.util.concurrent.atomic.AtomicReference<Throwable> escaped = new java.util.concurrent.atomic.AtomicReference<>();
        OutboundRequestRegistry registry = new OutboundRequestRegistry(connection, handler, capturing(escaped), counter);

        registry.start(currentWebSocket(), parsedWorkerEnvelope("req-f"));

        awaitValue(() -> escaped.get() == null ? 0 : 1, 1);
        assertThat(escaped.get()).isInstanceOf(AssertionError.class);
        assertThat(registry.size()).isZero();
        assertThat(counter.get()).isZero();
        assertNoFinalWithin("req-f", 300); // unchanged policy: an Error is not reported as ERROR
    }

    @Test
    void duplicateRequestOnSameConnection_isIgnored_whileTheFirstRuns() throws Exception {
        AtomicInteger handlerCalls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        connect(NodeRequestHandler.of(WorkerRequest.class, request -> {
            handlerCalls.incrementAndGet();
            await(release);
            return "first";
        }));

        server.sendTextFrame(workerFrame(NodeMessageType.REQUEST, "dup"));
        awaitValue(handlerCalls::get, 1);
        server.sendTextFrame(workerFrame(NodeMessageType.REQUEST, "dup"));
        assertNoFinalWithin("dup", 300);
        assertThat(handlerCalls).hasValue(1);
        assertThat(counter.get()).isEqualTo(1);
        assertThat(connection.activeRequestEntries()).isEqualTo(1);

        release.countDown();
        assertThat(awaitFinal("dup").get("type").asString()).isEqualTo("RESPONSE");
        assertNoFinalWithin("dup", 300);
        assertThat(handlerCalls).hasValue(1);
        assertThat(counter.get()).isZero();
    }

    @Test
    void cancelRacingConnectionLoss_cancelsOnce_andCleansUpWhenHandlerReturns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger interrupts = new AtomicInteger();
        connect(NodeRequestHandler.of(WorkerRequest.class, request -> {
            started.countDown();
            while (true) {
                try {
                    if (release.await(10, TimeUnit.SECONDS)) {
                        return "x";
                    }
                } catch (InterruptedException e) {
                    interrupts.incrementAndGet();
                }
            }
        }));

        server.sendTextFrame(workerFrame(NodeMessageType.REQUEST, "req-1"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        server.sendTextFrame(cancelFrame("req-1"));
        server.dropCurrentConnection();
        awaitValue(interrupts::get, 1);
        Thread.sleep(300); // a second cancel (CANCEL vs connection loss, whichever lost) must not interrupt again
        assertThat(interrupts.get()).isEqualTo(1);
        assertThat(counter.get()).isEqualTo(1);

        release.countDown();
        awaitValue(counter::get, 0);
        awaitValue(connection::activeRequestEntries, 0);
    }

    @Test
    void stop_endsRequestsWhoseHandlersReturn_andTracksOneIgnoringInterruptUntilItReturns() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        connect(NodeRequestHandler.of(WorkerRequest.class, request -> {
            bothStarted.countDown();
            boolean ignoresInterrupt = request.messages().get(0).content().equals("stubborn");
            while (true) {
                try {
                    if (release.await(10, TimeUnit.SECONDS)) {
                        return "x";
                    }
                } catch (InterruptedException e) {
                    if (!ignoresInterrupt) {
                        throw new IllegalStateException("interrupted");
                    }
                }
            }
        }), List.of(), true);

        server.sendTextFrame(JSON.writeValueAsString(NodeEnvelope.create(NodeMessageType.REQUEST, nodeId, "polite",
                new WorkerRequest(List.of(ChatMessage.user("polite")), null))));
        server.sendTextFrame(JSON.writeValueAsString(NodeEnvelope.create(NodeMessageType.REQUEST, nodeId, "stubborn",
                new WorkerRequest(List.of(ChatMessage.user("stubborn")), null))));
        assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.get()).isEqualTo(2);

        connection.stop(); // disconnect (cancels both) + messageExecutor.shutdownNow()
        awaitValue(counter::get, 1);
        assertThat(connection.activeRequestEntries()).isEqualTo(1); // the stubborn one is still running

        release.countDown();
        awaitValue(counter::get, 0);
        awaitValue(connection::activeRequestEntries, 0);
    }

    // Runs each task on its own thread and records a Throwable escaping it (instead of the JVM default handler).
    private static Executor capturing(java.util.concurrent.atomic.AtomicReference<Throwable> escaped) {
        return task -> new Thread(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                escaped.set(t);
            }
        }).start();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ unchanged behaviour

    @Test
    void routerRequest_canBeCancelled_butIsNeverCounted() throws Exception {
        record RouteRequest(String text) {
        }
        CountDownLatch started = new CountDownLatch(1);
        connect(NodeRequestHandler.of(RouteRequest.class, request -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return "never";
        }));

        server.sendTextFrame(JSON.writeValueAsString(NodeEnvelope.create(NodeMessageType.REQUEST, nodeId, "route-1", new RouteRequest("r"))));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.get()).isZero();
        server.sendTextFrame(cancelFrame("route-1"));
        assertThat(awaitFinal("route-1").at("/payload/code").asString()).isEqualTo(NodeError.CANCELLED);
        assertThat(counter.get()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private void connect(NodeRequestHandler<?, ?> handler) throws Exception {
        connect(handler, List.of());
    }

    private void connect(NodeRequestHandler<?, ?> handler, List<NodeMessageHandler> messageHandlers) throws Exception {
        connect(handler, messageHandlers, false);
    }

    // viaLifecycle: start() (SmartLifecycle) instead of connect(), so that stop() really shuts the connection down.
    private void connect(NodeRequestHandler<?, ?> handler, List<NodeMessageHandler> messageHandlers, boolean viaLifecycle) throws Exception {
        server = new FakeConsoleServer();
        NodeConnectionProperties properties = new NodeConnectionProperties();
        properties.setConnectionMode(NodeConnectionMode.OUTBOUND);
        properties.setConsoleUrl(server.baseUrl());
        properties.setNodeId(nodeId);
        properties.setCredential("credential");
        properties.getOutbound().setHeartbeatInterval(Duration.ofSeconds(30));
        properties.getOutbound().getReconnect().setInitialDelay(Duration.ofMillis(50));
        properties.getOutbound().getReconnect().setMaxDelay(Duration.ofMillis(100));
        connection = new WebSocketOutboundNodeConnection(properties, handler, null, messageHandlers, null, counter);
        if (viaLifecycle) {
            connection.start();
        } else {
            connection.connect();
        }
        awaitState(NodeConnectionState.CONNECTED);
    }

    private String workerFrame(NodeMessageType type, String requestId) {
        return JSON.writeValueAsString(NodeEnvelope.create(type, nodeId, requestId, new WorkerRequest(List.of(ChatMessage.user("hi")), null)));
    }

    private NodeEnvelope<JsonNode> parsedWorkerEnvelope(String requestId) {
        return NodeEnvelope.create(NodeMessageType.REQUEST, nodeId, requestId,
                JSON.valueToTree(new WorkerRequest(List.of(ChatMessage.user("hi")), null)));
    }

    private String cancelFrame(String requestId) {
        return JSON.writeValueAsString(NodeEnvelope.create(NodeMessageType.CANCEL, nodeId, requestId, null));
    }

    private WebSocket currentWebSocket() throws Exception {
        Field field = WebSocketOutboundNodeConnection.class.getDeclaredField("webSocket");
        field.setAccessible(true);
        return (WebSocket) field.get(connection);
    }

    private static WebSocket fakeWebSocket() {
        return (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(), new Class<?>[]{WebSocket.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "fake-websocket";
                    default -> null;
                });
    }

    // RESPONSE / ERROR frames by requestId, as "TYPE:code" ("-" for RESPONSE). Heartbeats are skipped.
    private Map<String, List<String>> collectFinals(int distinct, Duration timeout) throws InterruptedException {
        Map<String, List<String>> finals = new HashMap<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && (distinct == 0 || finals.size() < distinct)) {
            String frame = server.awaitTextFrame(1);
            if (frame == null) {
                continue;
            }
            JsonNode node = JSON.readTree(frame);
            String type = node.get("type").asString();
            if (type.equals("RESPONSE") || type.equals("ERROR")) {
                String code = type.equals("RESPONSE") ? "-" : node.at("/payload/code").asString();
                finals.computeIfAbsent(node.get("requestId").asString(), k -> new ArrayList<>()).add(type + ":" + code);
            }
        }
        return finals;
    }

    private static void merge(Map<String, List<String>> into, Map<String, List<String>> more) {
        more.forEach((k, v) -> into.computeIfAbsent(k, x -> new ArrayList<>()).addAll(v));
    }

    private JsonNode awaitFinal(String requestId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            String frame = server.awaitTextFrame(1);
            if (frame == null) {
                continue;
            }
            JsonNode node = JSON.readTree(frame);
            String type = node.get("type").asString();
            if ((type.equals("RESPONSE") || type.equals("ERROR")) && requestId.equals(node.get("requestId").asString())) {
                return node;
            }
        }
        throw new AssertionError("Timed out waiting for the final message of " + requestId);
    }

    private void assertNoFinalWithin(String requestId, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        do {
            String frame = server.awaitTextFrame(0);
            if (frame != null) {
                JsonNode node = JSON.readTree(frame);
                String type = node.get("type").asString();
                assertThat((type.equals("RESPONSE") || type.equals("ERROR")) && requestId.equals(node.get("requestId").asString()))
                        .as("unexpected final message for %s: %s", requestId, frame).isFalse();
            } else {
                Thread.sleep(20);
            }
        } while (System.nanoTime() < deadline);
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

    private void awaitState(NodeConnectionState expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (connection.currentState() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for state=" + expected + ", was=" + connection.currentState());
            }
            Thread.sleep(20);
        }
    }
}
