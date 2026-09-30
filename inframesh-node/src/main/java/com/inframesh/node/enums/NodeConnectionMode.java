package com.inframesh.node.enums;

/**
 * How Console and a node communicate.
 */
public enum NodeConnectionMode {

    /**
     * Console calls the node over HTTP. Current default.
     */
    DIRECT,

    /**
     * The node opens a persistent connection to Console. The connection runtime
     * is provided by infra-node's Outbound Connection SDK
     * ({@code com.inframesh.node.connection}).
     */
    OUTBOUND
}
