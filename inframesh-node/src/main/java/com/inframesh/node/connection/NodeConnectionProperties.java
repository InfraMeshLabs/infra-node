package com.inframesh.node.connection;

import com.inframesh.node.enums.NodeConnectionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.UUID;

/**
 * Configuration for how this node (Worker or Router) communicates with
 * InfraMesh Console ({@code inframesh.node.*}), independent of the
 * {@code infra.node.*} properties used to authenticate inbound DIRECT requests.
 */
@ConfigurationProperties(prefix = "inframesh.node")
public class NodeConnectionProperties {

    /**
     * How this node communicates with Console. Defaults to {@code DIRECT} so
     * existing nodes are unaffected unless explicitly opted in.
     */
    private NodeConnectionMode connectionMode = NodeConnectionMode.DIRECT;

    /**
     * Base URL of InfraMesh Console, e.g. {@code https://console.example.com}. Required
     * for OUTBOUND. {@code http}/{@code https} are mapped to {@code ws}/{@code wss};
     * {@code ws}/{@code wss} are used as-is.
     */
    private String consoleUrl;

    /**
     * Immutable nodeId issued at registration ({@code NodeRegistrationResponse.nodeId}).
     */
    private UUID nodeId;

    /**
     * Credential issued at registration. Never logged.
     */
    private String credential;

    /**
     * OUTBOUND connection settings. Only used when connection-mode is {@code OUTBOUND}.
     */
    private final Outbound outbound = new Outbound();

    public NodeConnectionMode getConnectionMode() {
        return connectionMode;
    }

    public void setConnectionMode(NodeConnectionMode connectionMode) {
        this.connectionMode = connectionMode;
    }

    public String getConsoleUrl() {
        return consoleUrl;
    }

    public void setConsoleUrl(String consoleUrl) {
        this.consoleUrl = consoleUrl;
    }

    public UUID getNodeId() {
        return nodeId;
    }

    public void setNodeId(UUID nodeId) {
        this.nodeId = nodeId;
    }

    public String getCredential() {
        return credential;
    }

    public void setCredential(String credential) {
        this.credential = credential;
    }

    public Outbound getOutbound() {
        return outbound;
    }

    public static class Outbound {

        /**
         * Interval between HEARTBEAT messages sent to Console while connected.
         */
        private Duration heartbeatInterval = Duration.ofSeconds(10);

        /**
         * Interval between HEALTH messages (node runtime health, {@code NodeHealthResponse}) sent to
         * Console while connected. Independent of the heartbeat: a HEARTBEAT only says the connection
         * is alive, HEALTH says how the node itself is doing ({@code CONNECTED != HEALTHY}).
         */
        private Duration healthInterval = Duration.ofSeconds(30);

        /**
         * Reconnect backoff settings (exponential backoff with full jitter).
         */
        private final Reconnect reconnect = new Reconnect();

        public Duration getHeartbeatInterval() {
            return heartbeatInterval;
        }

        public void setHeartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
        }

        public Duration getHealthInterval() {
            return healthInterval;
        }

        public void setHealthInterval(Duration healthInterval) {
            this.healthInterval = healthInterval;
        }

        public Reconnect getReconnect() {
            return reconnect;
        }

        public static class Reconnect {

            /**
             * Minimum delay before a reconnect attempt, and the base of the exponential backoff.
             */
            private Duration initialDelay = Duration.ofSeconds(1);

            /**
             * Upper bound of the reconnect delay.
             */
            private Duration maxDelay = Duration.ofSeconds(30);

            public Duration getInitialDelay() {
                return initialDelay;
            }

            public void setInitialDelay(Duration initialDelay) {
                this.initialDelay = initialDelay;
            }

            public Duration getMaxDelay() {
                return maxDelay;
            }

            public void setMaxDelay(Duration maxDelay) {
                this.maxDelay = maxDelay;
            }
        }
    }
}
