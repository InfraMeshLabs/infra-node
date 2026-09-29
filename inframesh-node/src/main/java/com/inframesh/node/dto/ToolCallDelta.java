package com.inframesh.node.dto;

/**
 * An incremental fragment of a {@link ToolCall} observed during streaming.
 * <p>
 * {@code index} correlates fragments belonging to the same tool call across
 * chunks (a turn may request more than one tool call in parallel).
 * {@code id} and {@code name} are populated on the fragment that opens a new
 * tool call and are {@code null} on subsequent fragments for the same index.
 * {@code argumentsDelta} is a raw fragment of the arguments JSON text; it is
 * not guaranteed to be valid JSON on its own and must be concatenated by
 * index before parsing.
 */
public record ToolCallDelta(
        Integer index,
        String id,
        String name,
        String argumentsDelta
) {
}
