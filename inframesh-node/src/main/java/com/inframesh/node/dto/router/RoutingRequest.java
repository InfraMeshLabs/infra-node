package com.inframesh.node.dto.router;

import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.ChatOptions;
import com.inframesh.node.dto.ToolChoice;
import com.inframesh.node.dto.ToolDefinition;

import java.util.List;
import java.util.UUID;

public record RoutingRequest(
        /**
         * Propagated from Console so a Router can preserve request context
         * across the boundary. Console — not Router — owns Session Affinity;
         * a Router is not required to use this value. Null when no session
         * is associated with the request.
         */
        String sessionId,

        List<ChatMessage> messages,
        ChatOptions options,
        RoutingHints routing,
        List<ToolDefinition> tools,
        ToolChoice toolChoice,
        List<WorkerCandidate> workers,

        /**
         * Console-authenticated identity of the caller, resolved from the
         * Member API Key — never taken from client input. Stable across
         * sessions, so a Router may use it to look up long-term state in its
         * own store; {@code sessionId} must not be used for that on its own.
         * Identifiers only, not credentials. Null when unknown (an older
         * Console, or a caller with no Team Member).
         */
        UUID teamUuid,
        UUID memberUuid
) {

    public RoutingRequest {
        tools = tools == null ? List.of() : List.copyOf(tools);
        workers = workers == null ? List.of() : List.copyOf(workers);
    }

    public RoutingRequest(String sessionId, List<ChatMessage> messages, ChatOptions options, RoutingHints routing,
                          List<ToolDefinition> tools, ToolChoice toolChoice, List<WorkerCandidate> workers) {
        this(sessionId, messages, options, routing, tools, toolChoice, workers, null, null);
    }

    public RoutingRequest(List<ChatMessage> messages, ChatOptions options, RoutingHints routing) {
        this(null, messages, options, routing, List.of(), null, List.of());
    }

    public RoutingRequest(List<ChatMessage> messages, ChatOptions options, RoutingHints routing, List<WorkerCandidate> workers) {
        this(null, messages, options, routing, List.of(), null, workers);
    }

    public RoutingRequest(List<ChatMessage> messages, ChatOptions options, RoutingHints routing, List<ToolDefinition> tools, ToolChoice toolChoice) {
        this(null, messages, options, routing, tools, toolChoice, List.of());
    }
}
