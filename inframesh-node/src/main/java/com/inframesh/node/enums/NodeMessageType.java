package com.inframesh.node.enums;

/**
 * Identifies a message exchanged over a persistent node connection.
 * Further types (streaming, cancel, shutdown) are added alongside the
 * outbound inference protocol.
 */
public enum NodeMessageType {
    CONNECT,
    CONNECT_ACK,
    HEARTBEAT,
    HEARTBEAT_ACK,
    REQUEST,
    RESPONSE,
    ERROR
}
