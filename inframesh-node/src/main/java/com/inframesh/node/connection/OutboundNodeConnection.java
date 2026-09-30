package com.inframesh.node.connection;

import com.inframesh.node.dto.connection.NodeEnvelope;

/**
 * Persistent outbound connection from a node (Worker or Router) to InfraMesh
 * Console, per the Outbound Connection Protocol.
 *
 * Implementations own the connect/reconnect/heartbeat lifecycle so that node
 * business code only depends on this abstraction and on
 * {@link NodeRequestHandler}/{@link NodeMessageHandler}, never on a specific
 * WebSocket client. Only node-level protocol types ({@link NodeEnvelope}) cross
 * this boundary - no Worker/Router business DTO appears in this API.
 */
public interface OutboundNodeConnection {

    void connect();

    void disconnect();

    boolean isConnected();

    /**
     * Sends an envelope over the current connection. A no-op (logged at debug)
     * while disconnected - callers are never failed by a transient disconnect.
     */
    void send(NodeEnvelope<?> message);
}
