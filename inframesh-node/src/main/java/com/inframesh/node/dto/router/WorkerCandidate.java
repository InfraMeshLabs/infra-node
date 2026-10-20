package com.inframesh.node.dto.router;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

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
        BigDecimal vramUsage,

        /**
         * The Worker's immutable external node UUID — the same value as its
         * {@code NodeRegistrationResponse.nodeId} / {@code NodeEnvelope.nodeId}.
         * Unlike {@link #workerId} (a Console-local database id, which is what
         * {@link RoutingResponse} returns), it stays unique across Console
         * databases, so a Router can safely persist it in an external store and
         * match it against later candidates. Re-registering a Worker issues a new
         * value. An identifier only, not a credential. Null from an older Console.
         */
        UUID nodeId
) {

    public WorkerCandidate(Long workerId, String name, String description, List<ModelInfo> models, String provider,
                           String framework, String frameworkVersion,
                           Integer currentRequestCount, Integer queueSize, BigDecimal averageLatency, BigDecimal throughput, Long errorCount,
                           String cpu, BigDecimal cpuUsage, String memory, BigDecimal memoryUsage,
                           String gpu, BigDecimal gpuUsage, String vram, BigDecimal vramUsage) {
        this(workerId, name, description, models, provider, framework, frameworkVersion,
                currentRequestCount, queueSize, averageLatency, throughput, errorCount,
                cpu, cpuUsage, memory, memoryUsage, gpu, gpuUsage, vram, vramUsage, null);
    }

    public WorkerCandidate {
        models = models == null
                ? List.of()
                : List.copyOf(models);
    }
}
