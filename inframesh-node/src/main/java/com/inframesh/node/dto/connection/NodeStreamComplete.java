package com.inframesh.node.dto.connection;

/**
 * Payload of a {@link com.inframesh.node.enums.NodeMessageType#STREAM_COMPLETE},
 * which ends the stream identified by {@link NodeEnvelope#requestId()} normally.
 *
 * Terminal result details (finish reason, usage) stay in the last chunk's data,
 * exactly as in DIRECT streaming; this message only marks the lifecycle end.
 *
 * @param chunkCount number of STREAM_CHUNKs sent for the stream (the last one had
 *                   sequence {@code chunkCount - 1}), letting a receiver verify it
 *                   saw every chunk.
 */
public record NodeStreamComplete(
        long chunkCount
) {

    public NodeStreamComplete {
        if (chunkCount < 0) {
            throw new IllegalArgumentException("chunkCount must not be negative: " + chunkCount);
        }
    }
}
