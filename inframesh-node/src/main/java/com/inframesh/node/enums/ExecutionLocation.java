package com.inframesh.node.enums;

/**
 * Where an AI model actually runs when a Worker selects it.
 * Router uses this to decide whether a Worker is safe for a private request.
 */
public enum ExecutionLocation {

    /**
     * Runs on infrastructure the user or InfraMesh manages/controls.
     */
    LOCAL,

    /**
     * Request data leaves to an external AI provider.
     */
    EXTERNAL
}
