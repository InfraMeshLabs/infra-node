package com.inframesh.node.dto.connection;

/**
 * Heartbeat payload sent by a node over its persistent connection to
 * signal that it is still alive.
 *
 * Carries no fields today; the heartbeat time is
 * {@link NodeEnvelope#timestamp()}. Reserved for future runtime status
 * (e.g. active requests, queue size).
 */
public record NodeHeartbeat() {
}
