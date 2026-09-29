package com.inframesh.node.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.inframesh.node.properties.NodeProperties;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class NodeApiKeyServletFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Infra-Api-Key";
    private static final String API_PATH_PREFIX = "/api/v1";

    private final NodeProperties properties;

    public NodeApiKeyServletFilter(NodeProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws IOException, jakarta.servlet.ServletException {
        if (!request.getRequestURI().startsWith(API_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String requestApiKey = request.getHeader(API_KEY_HEADER);

        if (requestApiKey == null || !properties.apiKey().toString().equals(requestApiKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
