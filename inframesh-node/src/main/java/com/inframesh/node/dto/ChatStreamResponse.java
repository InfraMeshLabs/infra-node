package com.inframesh.node.dto;

import com.inframesh.node.enums.FinishReason;

import java.util.List;

/**
 * A single streaming chunk of a chat invocation. A chunk may carry a text
 * {@code content} delta, one or more {@code toolCallDeltas}, and/or the
 * terminal {@code finishReason}/{@code usage} once the runtime signals the
 * turn is complete.
 */
public record ChatStreamResponse(
        String content,
        List<ToolCallDelta> toolCallDeltas,
        FinishReason finishReason,
        Usage usage
) {

    public ChatStreamResponse {
        toolCallDeltas = toolCallDeltas == null ? List.of() : List.copyOf(toolCallDeltas);
    }

    public ChatStreamResponse(String content) {
        this(content, List.of(), null, null);
    }
}
