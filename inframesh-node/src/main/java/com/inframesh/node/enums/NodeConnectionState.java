package com.inframesh.node.enums;

/**
 * State of a node's connection to Console.
 *
 * Independent of node health: a connection can be {@link #CONNECTED}
 * while the node's AI runtime is failing.
 */
public enum NodeConnectionState {
    CONNECTED,
    DISCONNECTED,
    RECONNECTING
}
