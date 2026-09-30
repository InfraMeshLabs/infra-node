package com.inframesh.node.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

/**
 * Inbound DIRECT request authentication ({@code infra.node.*}).
 *
 * @param apiKey API key that inbound DIRECT requests must present to this node
 */
@ConfigurationProperties(prefix = "infra.node")
public record NodeProperties(
        UUID apiKey
) {
}
