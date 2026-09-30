package com.inframesh.node.dto.connection;

/**
 * Payload of a {@link com.inframesh.node.enums.NodeMessageType#STREAM_CHUNK}.
 *
 * The stream a chunk belongs to is identified by {@link NodeEnvelope#requestId()}
 * (the requestId of the originating STREAM_REQUEST), so it is not repeated here.
 * {@code data} is the node's streaming result DTO - a Worker sends the same
 * {@code ChatStreamResponse} it streams over DIRECT, so both modes share one
 * streaming output contract.
 *
 * @param sequence position of this chunk within its stream: the first chunk is
 *                 {@code 0} and each following chunk is exactly one greater, so a
 *                 receiver can detect gaps and reordering.
 * @param data     the chunk content
 * @param <T>      streaming result type
 */
public record NodeStreamChunk<T>(
        long sequence,
        T data
) {

    public NodeStreamChunk {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative: " + sequence);
        }
    }
}
