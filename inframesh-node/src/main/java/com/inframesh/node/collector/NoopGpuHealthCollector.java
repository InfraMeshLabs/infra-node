package com.inframesh.node.collector;

import com.inframesh.node.dto.NodeHealthResponse.GpuInfo;

import java.util.List;

public class NoopGpuHealthCollector implements GpuHealthCollector {

    @Override
    public List<GpuInfo> getGpuInfo() {
        return List.of();
    }
}
