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
         * Correlates every message of one request lifecycle on the connection:
         * a REQUEST with its RESPONSE/ERROR, and a STREAM_REQUEST with all of its
         * STREAM_CHUNKs and its STREAM_COMPLETE/STREAM_ERROR. A CANCEL carries the
         * requestId of the request to cancel. Unique per request and unrelated to
         * the inference {@code sessionId}; {@code null} on messages that belong to
         * no request (HEARTBEAT, HEALTH, ...).
         */
        String requestId,

        T payload
) {

    /**
     * Creates an envelope with a fresh {@code messageId} and the current
     * timestamp - every message, e.g. each chunk of a stream, gets its own
     * messageId while sharing the request's {@code requestId}.
     */
    public static <T> NodeEnvelope<T> create(NodeMessageType type, UUID nodeId, String requestId, T payload) {
        return new NodeEnvelope<>(UUID.randomUUID().toString(), type, nodeId, Instant.now(), requestId, payload);
    }
}
