package com.inframesh.node.dto;

import com.inframesh.node.enums.ToolChoiceMode;

/**
 * Provider-independent tool-use policy for a {@link com.inframesh.node.dto.router.RoutingRequest}
 * or {@link com.inframesh.node.dto.worker.WorkerRequest}.
 * <p>
 * {@code toolName} is only meaningful (and required) when {@code mode} is
 * {@link ToolChoiceMode#TOOL}, forcing the model to call that specific tool.
 */
public record ToolChoice(
        ToolChoiceMode mode,
        String toolName
) {

    public static final ToolChoice AUTO = new ToolChoice(ToolChoiceMode.AUTO, null);
    public static final ToolChoice NONE = new ToolChoice(ToolChoiceMode.NONE, null);
    public static final ToolChoice REQUIRED = new ToolChoice(ToolChoiceMode.REQUIRED, null);

    public static ToolChoice tool(String toolName) {
        return new ToolChoice(ToolChoiceMode.TOOL, toolName);
    }
}
