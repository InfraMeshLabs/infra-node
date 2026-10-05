package com.inframesh.node.connection;

import com.inframesh.node.dto.connection.NodeEnvelope;
import com.inframesh.node.dto.connection.NodeError;
import com.inframesh.node.dto.connection.NodeStreamChunk;
import com.inframesh.node.dto.connection.NodeStreamComplete;
import com.inframesh.node.enums.NodeMessageType;
import com.inframesh.node.monitor.ActiveRequestCounter;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the streams a {@link NodeStreamRequestHandler} serves on behalf of
 * {@link WebSocketOutboundNodeConnection}: one {@link ActiveStream} per
 * in-flight STREAM_REQUEST, keyed by {@code requestId}.
 *
 * Every stream ends exactly once - on completion, error or cancellation,
 * whichever comes first ({@link ActiveStream#terminate()}) - and that single
 * point is where it leaves the registry and the active request count. A CANCEL
 * therefore never decrements the count itself; it cancels the stream, and the
 * stream's own termination does.
 *
 * Kept out of the connection class so {@code org.reactivestreams} types are
 * only loaded when a {@link NodeStreamRequestHandler} is actually configured.
 */
final class OutboundStreamRegistry<Q, T> {

    private static final Logger log = LoggerFactory.getLogger(OutboundStreamRegistry.class);

    private final WebSocketOutboundNodeConnection connection;
    private final NodeStreamRequestHandler<Q, T> handler;
    private final Executor executor;
    private final ActiveRequestCounter activeRequestCounter;
    private final JsonMapper jsonMapper = JsonMapper.shared();
    private final Map<String, ActiveStream> activeStreams = new ConcurrentHashMap<>();

    private OutboundStreamRegistry(WebSocketOutboundNodeConnection connection,
                                   NodeStreamRequestHandler<Q, T> handler,
                                   Executor executor,
                                   ActiveRequestCounter activeRequestCounter) {
        this.connection = connection;
        this.handler = handler;
        this.executor = executor;
        this.activeRequestCounter = activeRequestCounter;
    }

    static <Q, T> OutboundStreamRegistry<Q, T> create(WebSocketOutboundNodeConnection connection,
                                                      NodeStreamRequestHandler<Q, T> handler,
                                                      Executor executor,
                                                      ActiveRequestCounter activeRequestCounter) {
        return new OutboundStreamRegistry<>(connection, handler, executor, activeRequestCounter);
    }

    /**
     * Called on the WebSocket listener thread: the stream is registered here, before the handler
     * is handed to the executor, so a CANCEL arriving right behind its STREAM_REQUEST always
     * finds the stream it targets.
     */
    void start(WebSocket webSocket, NodeEnvelope<JsonNode> envelope) {
        String requestId = envelope.requestId();
        if (requestId == null) {
            log.warn("Received STREAM_REQUEST without a requestId, ignoring");
            return;
        }

        Q request;
        try {
            request = jsonMapper.treeToValue(envelope.payload(), handler.requestType());
        } catch (RuntimeException e) {
            log.warn("Failed to parse STREAM_REQUEST payload, requestId={}", requestId, e);
            executor.execute(() -> connection.sendIfSameConnection(webSocket,
                    connection.newEnvelope(NodeMessageType.STREAM_ERROR, requestId,
                            new NodeError(NodeError.MALFORMED_PAYLOAD, "Malformed request payload"))));
            return;
        }

        ActiveStream stream = new ActiveStream(webSocket, requestId);
        if (activeStreams.putIfAbsent(requestId, stream) != null) {
            log.warn("Received STREAM_REQUEST for an already active stream, ignoring. requestId={}", requestId);
            return;
        }
        activeRequestCounter.increment();

        executor.execute(() -> stream.run(request));
    }

    /** A CANCEL for an unknown or already finished stream is a no-op. */
    void cancel(String requestId, String reason) {
        ActiveStream stream = requestId == null ? null : activeStreams.get(requestId);
        if (stream != null) {
            stream.cancel(reason);
        }
    }

    void cancelAll(String reason) {
        for (ActiveStream stream : List.copyOf(activeStreams.values())) {
            stream.cancel(reason);
        }
    }

    private final class ActiveStream implements Subscriber<T> {

        private final WebSocket webSocket;
        private final String requestId;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicReference<Subscription> subscription = new AtomicReference<>();
        private final AtomicBoolean terminated = new AtomicBoolean();

        private ActiveStream(WebSocket webSocket, String requestId) {
            this.webSocket = webSocket;
            this.requestId = requestId;
        }

        void run(Q request) {
            if (terminated.get()) {
                return; // cancelled before the handler even started
            }

            Publisher<T> publisher;
            try {
                publisher = Objects.requireNonNull(handler.handle(request), "NodeStreamRequestHandler returned null");
            } catch (RuntimeException e) {
                onError(e);
                return;
            }
            publisher.subscribe(this);
        }

        @Override
        public void onSubscribe(Subscription s) {
            subscription.set(s);
            // cancel() may have run before the subscription existed; honor it now.
            if (terminated.get()) {
                s.cancel();
            } else {
                s.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(T item) {
            if (terminated.get()) {
                return;
            }
            send(NodeMessageType.STREAM_CHUNK, new NodeStreamChunk<>(sequence.getAndIncrement(), item));
        }

        @Override
        public void onError(Throwable error) {
            if (!terminate()) {
                return;
            }
            log.warn("Outbound stream failed, requestId={}", requestId, error);
            send(NodeMessageType.STREAM_ERROR, new NodeError(NodeError.REQUEST_FAILED, String.valueOf(error.getMessage())));
        }

        @Override
        public void onComplete() {
            if (!terminate()) {
                return;
            }
            send(NodeMessageType.STREAM_COMPLETE, new NodeStreamComplete(sequence.get()));
        }

        void cancel(String reason) {
            if (!terminate()) {
                return;
            }
            Subscription s = subscription.get();
            if (s != null) {
                s.cancel();
            }
            log.info("Cancelled outbound stream, requestId={}, reason={}", requestId, reason);
        }

        // The one place a stream ends, whichever of complete/error/cancel gets here first.
        private boolean terminate() {
            if (!terminated.compareAndSet(false, true)) {
                return false;
            }
            activeStreams.remove(requestId, this);
            activeRequestCounter.decrement();
            return true;
        }

        private void send(NodeMessageType type, Object payload) {
            connection.sendIfSameConnection(webSocket, connection.newEnvelope(type, requestId, payload));
        }
    }
}
