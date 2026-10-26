package com.inframesh.node.connection;

import com.inframesh.node.dto.connection.NodeError;

/**
 * An exception that knows the {@link NodeError} Console should receive for it.
 *
 * When a {@link NodeRequestHandler} or {@link NodeStreamRequestHandler} fails with an exception
 * implementing this interface, the connection SDK sends {@link #nodeError()} as the ERROR /
 * STREAM_ERROR payload instead of {@code NodeError(REQUEST_FAILED, exception message)}. This lets
 * a node report a machine-readable {@code code} Console can act on - a Router refusing a request
 * for a policy reason, say - rather than a generic failure.
 *
 * It is an interface, not a base class, so an exception that already extends something else
 * (e.g. Spring's {@code ResponseStatusException}, which a node's DIRECT HTTP path relies on) can
 * carry a code too. {@link NodeRequestException} is a ready-made implementation.
 *
 * Only a short, client-safe description belongs in the message - never a stack trace,
 * credential, request content or other internal detail.
 */
public interface NodeErrorCarrier {

    /**
     * The payload to send. A {@code null} result, or one with a blank code, falls back to
     * {@code NodeError(REQUEST_FAILED, exception message)}.
     */
    NodeError nodeError();

    /** The payload the SDK sends for a handler failure. */
    static NodeError toNodeError(Throwable failure) {
        if (failure instanceof NodeErrorCarrier carrier) {
            NodeError error = carrier.nodeError();
            if (error != null && error.code() != null && !error.code().isBlank()) {
                return error;
            }
        }
        return new NodeError(NodeError.REQUEST_FAILED, String.valueOf(failure.getMessage()));
    }
}
