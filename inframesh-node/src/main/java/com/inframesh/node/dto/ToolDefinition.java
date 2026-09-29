package com.inframesh.node.dto;

import tools.jackson.databind.JsonNode;

/**
 * Describes a tool made available to the model. {@code parameters} carries a
 * JSON Schema object describing the tool's argument shape.
 */
public record ToolDefinition(
        String name,
        String description,
        JsonNode parameters
) {
}
