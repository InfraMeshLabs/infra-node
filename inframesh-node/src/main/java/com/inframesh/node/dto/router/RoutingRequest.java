package com.inframesh.node.dto.router;

import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.ChatOptions;
import com.inframesh.node.dto.ToolChoice;
import com.inframesh.node.dto.ToolDefinition;

import java.util.List;

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
        List<WorkerCandidate> workers
) {

    public RoutingRequest {
        tools = tools == null ? List.of() : List.copyOf(tools);
        workers = workers == null ? List.of() : List.copyOf(workers);
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
