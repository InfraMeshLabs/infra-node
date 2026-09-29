package com.inframesh.node.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

@ConfigurationProperties(prefix = "infra.node")
public record NodeProperties(
        UUID apiKey
) {
}
