package com.inframesh.node.collector;

import com.inframesh.node.dto.NodeHealthResponse.CpuInfo;
import com.inframesh.node.dto.NodeHealthResponse.MemoryInfo;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;

public class SystemHealthCollector {

    private final SystemInfo systemInfo = new SystemInfo();

    public CpuInfo getCpuInfo() {
        CentralProcessor processor = systemInfo.getHardware().getProcessor();

        String name = processor.getProcessorIdentifier().getName();
        double usage = processor.getSystemCpuLoad(1000) * 100;

        return new CpuInfo(name, round(usage));
    }

    public MemoryInfo getMemoryInfo() {
        GlobalMemory memory = systemInfo.getHardware().getMemory();

        long total = memory.getTotal();
        long available = memory.getAvailable();
        long used = total - available;

        return new MemoryInfo(total, used, available);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
