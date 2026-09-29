package com.inframesh.node.dto;

import com.inframesh.node.enums.FinishReason;

import java.util.List;

/**
 * Result of a non-streaming chat invocation. The response is always from the
 * assistant; {@code toolCalls} is populated when the model requests one or
 * more tool invocations instead of, or alongside, {@code message}.
 */
public record ChatResponse(
        String message,
        List<ToolCall> toolCalls,
        FinishReason finishReason,
        Usage usage
) {

    public ChatResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public ChatResponse(String message, Usage usage) {
        this(message, List.of(), null, usage);
    }
}
