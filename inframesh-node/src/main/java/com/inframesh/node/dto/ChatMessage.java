package com.inframesh.node.dto;

import com.inframesh.node.enums.ChatRole;

import java.util.List;

/**
 * A single turn in a chat conversation.
 * <p>
 * An {@link ChatRole#ASSISTANT} message may carry {@code toolCalls} requested
 * by the model. A {@link ChatRole#TOOL} message reports the result of a tool
 * call back to the model via {@code toolCallId} and {@code content}.
 */
public record ChatMessage(
        ChatRole role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId
) {

    public ChatMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public ChatMessage(ChatRole role, String content) {
        this(role, content, List.of(), null);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ChatRole.ASSISTANT, content);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(ChatRole.ASSISTANT, content, toolCalls, null);
    }

    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage(ChatRole.TOOL, content, List.of(), toolCallId);
    }
}
