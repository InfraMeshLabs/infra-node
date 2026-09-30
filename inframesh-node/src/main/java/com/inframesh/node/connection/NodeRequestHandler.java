package com.inframesh.node.connection;

import java.util.Objects;
import java.util.function.Function;

/**
 * Serves a {@link com.inframesh.node.enums.NodeMessageType#REQUEST} received
 * over the outbound connection and produces the payload of the matching
 * {@link com.inframesh.node.enums.NodeMessageType#RESPONSE}.
 *
 * The connection SDK only transports: it deserializes the REQUEST payload into
 * {@link #requestType()}, runs {@link #handle(Object)} off the WebSocket
 * listener thread, and wraps the result in a RESPONSE envelope carrying the
 * same {@code requestId}. What the payload means is up to the node - a Worker
 * registers a handler for {@code WorkerRequest} (inference), a Router for
 * {@code RoutingRequest} (routing decision).
 *
 * Because the handler runs on its own thread, implementations may block (e.g.
 * {@code .block()} on an existing reactive service) without stalling
 * heartbeats or other in-flight requests. Throwing from {@link #handle(Object)}
 * is reported back to Console as an ERROR envelope carrying the exception
 * message, rather than leaving Console waiting for the request timeout.
 *
 * @param <Q> REQUEST payload type
 * @param <R> RESPONSE payload type
 */
public interface NodeRequestHandler<Q, R> {

    Class<Q> requestType();

    R handle(Q request);

    static <Q, R> NodeRequestHandler<Q, R> of(Class<Q> requestType, Function<? super Q, ? extends R> handler) {
        Objects.requireNonNull(requestType, "requestType");
        Objects.requireNonNull(handler, "handler");

        return new NodeRequestHandler<>() {
            @Override
            public Class<Q> requestType() {
                return requestType;
            }

            @Override
            public R handle(Q request) {
                return handler.apply(request);
            }
        };
    }
}
