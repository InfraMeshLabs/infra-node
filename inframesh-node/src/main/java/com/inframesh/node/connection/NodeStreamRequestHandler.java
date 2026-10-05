package com.inframesh.node.connection;

import org.reactivestreams.Publisher;

import java.util.Objects;
import java.util.function.Function;

/**
 * Serves a {@link com.inframesh.node.enums.NodeMessageType#STREAM_REQUEST}
 * received over the outbound connection: the streaming counterpart of
 * {@link NodeRequestHandler}.
 *
 * The connection SDK owns the stream lifecycle: it deserializes the
 * STREAM_REQUEST payload into {@link #requestType()}, subscribes to the
 * {@link Publisher} returned by {@link #handle(Object)}, sends every element
 * as a STREAM_CHUNK (sequence starting at {@code 0}) and ends the stream with
 * STREAM_COMPLETE, or STREAM_ERROR if the publisher fails or
 * {@link #handle(Object)} throws. A CANCEL for the stream's {@code requestId},
 * or the loss of the connection, cancels the subscription and sends nothing
 * further.
 *
 * A Worker registers a handler for {@code WorkerRequest} returning its
 * streaming inference (a Reactor {@code Flux} is a {@link Publisher}). For
 * such a handler the SDK also keeps
 * {@link com.inframesh.node.monitor.ActiveRequestCounter} for the whole stream
 * - the handler must not count the request itself.
 *
 * When no such handler is registered, STREAM_REQUEST and CANCEL are delivered
 * to {@link NodeMessageHandler}s instead, exactly as before.
 *
 * Requires {@code org.reactivestreams:reactive-streams} on the classpath
 * (transitively present with Reactor).
 *
 * @param <Q> STREAM_REQUEST payload type
 * @param <T> STREAM_CHUNK data type
 */
public interface NodeStreamRequestHandler<Q, T> {

    Class<Q> requestType();

    Publisher<T> handle(Q request);

    static <Q, T> NodeStreamRequestHandler<Q, T> of(Class<Q> requestType,
                                                    Function<? super Q, ? extends Publisher<T>> handler) {
        Objects.requireNonNull(requestType, "requestType");
        Objects.requireNonNull(handler, "handler");

        return new NodeStreamRequestHandler<>() {
            @Override
            public Class<Q> requestType() {
                return requestType;
            }

            @Override
            public Publisher<T> handle(Q request) {
                return handler.apply(request);
            }
        };
    }
}
