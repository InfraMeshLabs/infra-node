package com.inframesh.node.collector;

import com.inframesh.node.dto.NodeHealthResponse.GpuInfo;

import java.util.List;

public interface GpuHealthCollector {

    List<GpuInfo> getGpuInfo();
}
