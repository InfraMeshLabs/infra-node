package com.inframesh.node.dto.connection;

import com.inframesh.node.enums.NodeMessageType;

/**
 * Common envelope for every message on a persistent node connection.
 *
 * Credentials are never carried here: they are verified once during the
 * connection handshake, after which messages belong to that connection.
 */
public record NodeEnvelope<T>(
        /**
         * Correlates a request with its response on the connection. Unique
         * per request and unrelated to the inference {@code sessionId}.
         */
        String requestId,

        NodeMessageType type,
        T payload
) {
}
