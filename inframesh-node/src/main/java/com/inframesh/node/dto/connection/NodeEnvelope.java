package com.inframesh.node.dto.connection;

import com.inframesh.node.enums.NodeMessageType;

import java.time.Instant;
import java.util.UUID;

/**
 * Common envelope for every message on a persistent node connection.
 *
 * Credentials are never carried here: they are verified once during the
 * connection handshake, after which messages belong to that connection.
 */
public record NodeEnvelope<T>(
        /**
         * Identifies this message itself, for debugging and tracing.
         * Unrelated to {@link #requestId}, which correlates a request with
         * its response.
         */
        String messageId,

        NodeMessageType type,

        /**
         * External identity of the sending node, issued at registration
         * ({@code NodeRegistrationResponse.nodeId}). A connection is
         * authenticated to a single nodeId; Console must verify this value
         * matches that authenticated identity and never trust it as-is.
         */
        UUID nodeId,

        Instant timestamp,

        /**
         * Correlates a request with its response on the connection. Unique
         * per request and unrelated to the inference {@code sessionId}.
         */
        String requestId,

        T payload
) {
}
