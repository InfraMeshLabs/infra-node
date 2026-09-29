package com.inframesh.node.service;

import com.inframesh.node.collector.GpuHealthCollector;
import com.inframesh.node.collector.SystemHealthCollector;
import com.inframesh.node.dto.NodeHealthResponse;
import com.inframesh.node.dto.NodeHealthResponse.RuntimeInfo;
import com.inframesh.node.dto.NodeHealthResponse.SystemInfo;
import com.inframesh.node.enums.NodeStatus;
import com.inframesh.node.monitor.ActiveRequestCounter;

public class NodeHealthService {

    private final SystemHealthCollector systemHealthCollector;
    private final GpuHealthCollector gpuHealthCollector;
    private final ActiveRequestCounter activeRequestCounter;

    public NodeHealthService(SystemHealthCollector systemHealthCollector, GpuHealthCollector gpuHealthCollector, ActiveRequestCounter activeRequestCounter) {
        this.systemHealthCollector = systemHealthCollector;
        this.gpuHealthCollector = gpuHealthCollector;
        this.activeRequestCounter = activeRequestCounter;
    }

    public NodeHealthResponse getHealth() {
        return new NodeHealthResponse(
                NodeStatus.UP,
                new SystemInfo(
                        systemHealthCollector.getCpuInfo(),
                        systemHealthCollector.getMemoryInfo(),
                        gpuHealthCollector.getGpuInfo()
                ),
                new RuntimeInfo(
                        activeRequestCounter.get()
                )
        );
    }
}
