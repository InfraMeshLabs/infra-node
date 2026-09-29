package com.inframesh.node.dto.router;

import java.math.BigDecimal;
import java.util.List;

/**
 * A Worker made available to the Router as a routing candidate.
 *
 * Console builds this from the Worker list it already holds for the request
 * and sends it as part of {@link RoutingRequest}.
 */
public record WorkerCandidate(
        Long workerId,
        String name,
        String description,

        List<ModelInfo> models,
        String provider,

        String framework,
        String frameworkVersion,

        Integer currentRequestCount,
        Integer queueSize,
        BigDecimal averageLatency,
        BigDecimal throughput,
        Long errorCount,

        String cpu,
        BigDecimal cpuUsage,

        String memory,
        BigDecimal memoryUsage,

        String gpu,
        BigDecimal gpuUsage,

        String vram,
        BigDecimal vramUsage
) {

    public WorkerCandidate {
        models = models == null
                ? List.of()
                : List.copyOf(models);
    }
}
