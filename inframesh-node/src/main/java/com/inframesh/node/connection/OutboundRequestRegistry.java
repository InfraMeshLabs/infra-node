package com.inframesh.node.connection;

import com.inframesh.node.dto.connection.NodeEnvelope;
import com.inframesh.node.dto.connection.NodeError;
import com.inframesh.node.enums.NodeMessageType;
import com.inframesh.node.monitor.ActiveRequestCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs the non-streaming REQUESTs a {@link NodeRequestHandler} serves on behalf of
 * {@link WebSocketOutboundNodeConnection}: one {@link ActiveRequest} per in-flight REQUEST,
 * keyed by the connection it arrived on and its {@code requestId}.
 *
 * A CANCEL for a request that has not started yet stops it from starting; for a running one it
 * interrupts the handler's thread. Either way the request leaves the registry, the active count
 * and sends its single final message only when the handler call has actually returned (or never
 * started) - not when the cancel was requested. A handler that ignores the interrupt therefore
 * stays tracked and counted until it returns. A cancelled request ends with
 * {@code ERROR(}{@link NodeError#CANCELLED}{@code )}, never a RESPONSE, even if the handler returns a result.
 *
 * Every request ends exactly once, whichever of completion / failure / cancellation decides it
 * first; the decision and the interrupt are taken under the request's lock so the handler thread
 * is never interrupted after it left the handler (it is a pooled thread).
 */
final class OutboundRequestRegistry {

    private static final Logger log = LoggerFactory.getLogger(OutboundRequestRegistry.class);

    private enum State { QUEUED, RUNNING, DONE }

    private record Key(WebSocket webSocket, String requestId) {
    }

    private final WebSocketOutboundNodeConnection connection;
    private final NodeRequestHandler<?, ?> handler;
    private final Executor executor;
    private final ActiveRequestCounter activeRequestCounter;
    private final JsonMapper jsonMapper = JsonMapper.shared();
    private final Map<Key, ActiveRequest> activeRequests = new ConcurrentHashMap<>();

    OutboundRequestRegistry(WebSocketOutboundNodeConnection connection,
                            NodeRequestHandler<?, ?> handler,
                            Executor executor,
                            ActiveRequestCounter activeRequestCounter) {
        this.connection = connection;
        this.handler = handler;
        this.executor = executor;
        this.activeRequestCounter = activeRequestCounter;
    }

    /**
     * Called on the WebSocket listener thread: the request is registered before it is handed to the
     * executor, so a CANCEL arriving right behind its REQUEST always finds it.
     */
    void start(WebSocket webSocket, NodeEnvelope<JsonNode> envelope) {
        ActiveRequest request = new ActiveRequest(new Key(webSocket, envelope.requestId()), envelope.payload());
        if (activeRequests.putIfAbsent(request.key, request) != null) {
            log.warn("Received REQUEST for an already active request, ignoring. requestId={}", envelope.requestId());
            return;
        }

        try {
            executor.execute(request::run);
        } catch (RejectedExecutionException e) {
            // The node is shutting down. Nothing ran, so nothing to count down - just leave the registry.
            log.warn("Could not start REQUEST, executor rejected it. requestId={}", envelope.requestId());
            request.finish(connection.newEnvelope(NodeMessageType.ERROR, envelope.requestId(),
                    new NodeError(NodeError.REQUEST_FAILED, "Node is shutting down")));
        }
    }

    /** A CANCEL for an unknown or already finished request is a no-op. */
    void cancel(WebSocket webSocket, String requestId) {
        ActiveRequest request = activeRequests.get(new Key(webSocket, requestId));
        if (request != null) {
            request.cancel("CANCEL received");
        }
    }

    /** Cancels every request that arrived on {@code webSocket} ({@code null}: on any connection). */
    void cancelAll(WebSocket webSocket, String reason) {
        for (ActiveRequest request : List.copyOf(activeRequests.values())) {
            if (webSocket == null || request.key.webSocket == webSocket) {
                request.cancel(reason);
            }
        }
    }

    /** Package-private test seam: requests not yet ended (queued, running, or cancelled but still running). */
    int size() {
        return activeRequests.size();
    }

    private final class ActiveRequest {

        private final Key key;
        private final JsonNode payload;
        // Guarded by this: the state, the cancel decision and the interrupt are one atomic step.
        private State state = State.QUEUED;
        private boolean cancelRequested;
        private Thread runner;

        private ActiveRequest(Key key, JsonNode payload) {
            this.key = key;
            this.payload = payload;
        }

        void run() {
            synchronized (this) {
                if (state != State.QUEUED) {
                    return; // cancelled before it started - cancel() already ended it
                }
                state = State.RUNNING;
                runner = Thread.currentThread();
            }

            NodeEnvelope<?> outcome = null;
            boolean counted = false;
            try {
                Object request;
                try {
                    request = jsonMapper.treeToValue(payload, handler.requestType());
                } catch (RuntimeException e) {
                    log.warn("Failed to parse REQUEST payload, requestId={}", key.requestId, e);
                    outcome = connection.newEnvelope(NodeMessageType.ERROR, key.requestId,
                            new NodeError(NodeError.MALFORMED_PAYLOAD, "Malformed request payload"));
                    return;
                }

                // Active only while the handler runs - sending the RESPONSE/ERROR afterwards is not inference.
                activeRequestCounter.increment();
                counted = true;
                try {
                    outcome = connection.newEnvelope(NodeMessageType.RESPONSE, key.requestId, handle(request));
                } catch (RuntimeException e) {
                    outcome = failure(e);
                }
            } finally {
                end(outcome, counted);
            }
        }

        // The handler call has returned (or never started): from here on nothing may interrupt this
        // pooled thread. outcome is null only if the handler threw an Error: an uncancelled request then
        // sends nothing, as before cancellation existed; a cancelled one still sends ERROR(CANCELLED) -
        // the handler did end - and the Error keeps propagating to the executor either way.
        private void end(NodeEnvelope<?> outcome, boolean counted) {
            boolean cancelled;
            synchronized (this) {
                state = State.DONE;
                runner = null;
                cancelled = cancelRequested;
            }
            Thread.interrupted(); // clear an interrupt the handler did not consume
            if (counted) {
                activeRequestCounter.decrement();
            }
            if (cancelled) {
                log.info("Cancelled outbound request ended, requestId={}", key.requestId);
                outcome = cancelledEnvelope();
            }
            if (outcome != null) {
                finish(outcome);
            } else {
                activeRequests.remove(key, this);
            }
        }

        @SuppressWarnings("unchecked")
        private Object handle(Object request) {
            return ((NodeRequestHandler<Object, ?>) handler).handle(request);
        }

        private NodeEnvelope<?> failure(RuntimeException e) {
            log.warn("Outbound request failed, requestId={}", key.requestId, e);
            return connection.newEnvelope(NodeMessageType.ERROR, key.requestId, NodeErrorCarrier.toNodeError(e));
        }

        void cancel(String reason) {
            boolean endNow;
            synchronized (this) {
                if (state == State.DONE || cancelRequested) {
                    return; // already ended, or already cancelled
                }
                cancelRequested = true;
                endNow = state == State.QUEUED;
                if (endNow) {
                    state = State.DONE; // the queued run() will see this and not start the handler
                } else {
                    runner.interrupt();
                }
            }
            log.info("Cancelling outbound request, requestId={}, started={}, reason={}", key.requestId, !endNow, reason);
            if (endNow) {
                finish(cancelledEnvelope());
            }
        }

        private NodeEnvelope<?> cancelledEnvelope() {
            return connection.newEnvelope(NodeMessageType.ERROR, key.requestId, new NodeError(NodeError.CANCELLED, "Request cancelled"));
        }

        // The one place a request leaves the registry; its final message is sent at most once, after
        // that, and only over the connection it arrived on. A failed send leaves nothing behind.
        void finish(NodeEnvelope<?> finalMessage) {
            activeRequests.remove(key, this);
            connection.sendIfSameConnection(key.webSocket, finalMessage);
        }
    }
}
