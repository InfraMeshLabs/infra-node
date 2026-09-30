package com.inframesh.node.dto.connection;

/**
 * Payload of {@link com.inframesh.node.enums.NodeMessageType#ERROR} and
 * {@link com.inframesh.node.enums.NodeMessageType#STREAM_ERROR}. The failed
 * request, if any, is identified by {@link NodeEnvelope#requestId()}.
 *
 * Only a short, client-safe description belongs here - never a stack trace,
 * credential or other internal detail.
 *
 * @param code    machine-readable error code; the constants below are the ones the
 *                connection SDK itself produces, nodes may use their own. May be
 *                {@code null} on messages from senders predating this field.
 * @param message human-readable description
 */
public record NodeError(
        String code,
        String message
) {

    /** The request payload could not be deserialized. */
    public static final String MALFORMED_PAYLOAD = "MALFORMED_PAYLOAD";

    /** The node has no handler for this kind of request. */
    public static final String UNSUPPORTED_REQUEST = "UNSUPPORTED_REQUEST";

    /** The node's handler failed while serving the request. */
    public static final String REQUEST_FAILED = "REQUEST_FAILED";
}
