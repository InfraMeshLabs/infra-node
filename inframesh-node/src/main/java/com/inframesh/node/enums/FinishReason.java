package com.inframesh.node.enums;

/**
 * Provider-independent reason an assistant turn ended.
 * Runtimes/converters map their own provider-specific values onto this set.
 */
public enum FinishReason {
    STOP,
    TOOL_CALLS,
    LENGTH,
    CONTENT_FILTER,
    ERROR
}
