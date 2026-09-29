package com.inframesh.node.dto.router;

import com.inframesh.node.enums.ExecutionLocation;

/**
 * One AI a Worker can use to serve a request.
 * <p>
 * {@code executionLocation} may be {@code null} for candidates registered
 * before this field existed; Router must treat an unknown location as unsafe
 * for private requests (fail-closed).
 */
public record ModelInfo(
        String provider,
        String modelName,
        ExecutionLocation executionLocation
) {
}
