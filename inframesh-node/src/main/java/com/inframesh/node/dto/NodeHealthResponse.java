package com.inframesh.node.dto;

import com.inframesh.node.enums.NodeStatus;

import java.util.List;

public record NodeHealthResponse(
        NodeStatus status,
        SystemInfo system,
        RuntimeInfo runtime
) {

    public record SystemInfo(
            CpuInfo cpu,
            MemoryInfo memory,
            List<GpuInfo> gpus
    ) {
    }

    public record RuntimeInfo(
            Integer activeRequests
    ) {
    }

    public record CpuInfo(
            String name,
            Double usage
    ) {
    }

    public record MemoryInfo(
            Long total,
            Long used,
            Long available
    ) {
    }

    public record GpuInfo(
            Integer index,
            String name,
            Double usage,
            Long totalMemory,
            Long usedMemory,
            Long availableMemory
    ) {
    }

}
