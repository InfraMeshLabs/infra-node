package com.inframesh.node.dto;

import tools.jackson.databind.JsonNode;

/**
 * A tool invocation requested by the model. {@code arguments} carries the
 * runtime's argument payload losslessly, as a parsed JSON tree.
 */
public record ToolCall(
        String id,
        String name,
        JsonNode arguments
) {
}
