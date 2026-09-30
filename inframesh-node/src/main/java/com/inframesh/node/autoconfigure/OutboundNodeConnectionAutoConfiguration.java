package com.inframesh.node.autoconfigure;

import com.inframesh.node.connection.NodeConnectionProperties;
import com.inframesh.node.connection.NodeMessageHandler;
import com.inframesh.node.connection.NodeRequestHandler;
import com.inframesh.node.connection.OutboundNodeConnection;
import com.inframesh.node.connection.WebSocketOutboundNodeConnection;
import com.inframesh.node.service.NodeHealthService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the persistent Console connection for any node (Worker or Router),
 * only when it is configured for {@code inframesh.node.connection-mode: OUTBOUND}.
 *
 * When the property is absent or set to {@code DIRECT}, no bean is created
 * and no outbound WebSocket client is started - existing DIRECT nodes are
 * completely unaffected by having infra-node on the classpath.
 */
@AutoConfiguration(after = NodeAutoConfiguration.class)
@EnableConfigurationProperties(NodeConnectionProperties.class)
@ConditionalOnProperty(prefix = "inframesh.node", name = "connection-mode", havingValue = "OUTBOUND")
public class OutboundNodeConnectionAutoConfiguration {

    /**
     * The {@link NodeRequestHandler} bean is optional: a node that opts into OUTBOUND but
     * defines none still connects and heartbeats normally - it just rejects REQUESTs with an
     * ERROR instead of serving them. At most one may be defined.
     * <p>
     * HEALTH is reported from the same {@link NodeHealthService} behind DIRECT's
     * {@code GET /api/v1/health}, so Worker and Router push exactly the payload Console would
     * otherwise pull. Without that bean the node connects and heartbeats but reports no HEALTH.
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboundNodeConnection outboundNodeConnection(NodeConnectionProperties properties,
                                                         ObjectProvider<NodeRequestHandler<?, ?>> requestHandler,
                                                         ObjectProvider<NodeMessageHandler> messageHandlers,
                                                         ObjectProvider<NodeHealthService> healthService) {
        NodeHealthService health = healthService.getIfAvailable();
        return new WebSocketOutboundNodeConnection(
                properties, requestHandler.getIfAvailable(), messageHandlers.orderedStream().toList(),
                health != null ? health::getHealth : null);
    }
}
