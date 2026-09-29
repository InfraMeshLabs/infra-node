package com.inframesh.node.filter;

import com.inframesh.node.properties.NodeProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

public class NodeApiKeyFilter implements WebFilter {

    private static final String API_KEY_HEADER = "X-Infra-Api-Key";
    private static final String API_PATH_PREFIX = "/api/v1";

    private final NodeProperties properties;

    public NodeApiKeyFilter(NodeProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith(API_PATH_PREFIX)) {
            return chain.filter(exchange);
        }

        String requestApiKey = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);

        if (requestApiKey == null || !properties.apiKey().toString().equals(requestApiKey)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }
}
