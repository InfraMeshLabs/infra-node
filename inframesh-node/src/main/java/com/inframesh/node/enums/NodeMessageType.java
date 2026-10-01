package com.inframesh.node.enums;

/**
 * Identifies a message exchanged over a persistent node connection.
 *
 * Every message belonging to one inference carries that inference's
 * {@code NodeEnvelope.requestId}; {@code messageId} always identifies the
 * individual message only.
 *
 * <pre>
 * Non-streaming   REQUEST ─▶ RESPONSE | ERROR
 * Streaming       STREAM_REQUEST ─▶ STREAM_CHUNK* ─▶ STREAM_COMPLETE | STREAM_ERROR
 * </pre>
 */
public enum NodeMessageType {
    CONNECT,
    CONNECT_ACK,

    HEARTBEAT,
    HEARTBEAT_ACK,

    HEALTH,
    HEALTH_ACK,

    REQUEST,
    RESPONSE,

    /** Console → node. Starts a streaming inference; payload is the same request DTO as REQUEST. */
    STREAM_REQUEST,
    /** Node → Console. One part of a streaming result; payload {@code NodeStreamChunk}. */
    STREAM_CHUNK,
    /** Node → Console. The stream ended normally; payload {@code NodeStreamComplete}. */
    STREAM_COMPLETE,
    /** Node → Console. The stream failed and ends here; payload {@code NodeError}. */
    STREAM_ERROR,

    /**
     * Console → node. Asks the node to stop the in-flight request identified by
     * the envelope's {@code requestId}; no payload. Not tied to streaming.
     */
    CANCEL,

    /**
     * Connection/protocol-level error, or the failure of a non-streaming
     * REQUEST (then carrying its {@code requestId}); payload {@code NodeError}.
     */
    ERROR
}
