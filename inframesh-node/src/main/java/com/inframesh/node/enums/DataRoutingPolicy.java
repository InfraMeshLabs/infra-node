package com.inframesh.node.enums;

/**
 * Team-level rule for which Workers an AI_ROUTER Router may pick, sent by Console in
 * {@link com.inframesh.node.dto.router.RoutingRequest#dataRoutingPolicy()}.
 * {@code privateData=true} always wins: it restricts to LOCAL-only Workers under every policy.
 */
public enum DataRoutingPolicy {

    /**
     * Router judges whether the request holds sensitive data; a sensitive (or unjudged) request
     * must go to a LOCAL-only Worker. The default, and what a missing value means.
     */
    AUTO,

    /**
     * No sensitivity judgement; only LOCAL-only Workers. Console already sends only those as candidates.
     */
    INTERNAL_ONLY,

    /**
     * No sensitivity judgement; any candidate may be picked. Not a preference for EXTERNAL Workers.
     */
    EXTERNAL_ALLOWED
}
