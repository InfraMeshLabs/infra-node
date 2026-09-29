package com.inframesh.node.collector;

import com.inframesh.node.dto.NodeHealthResponse.GpuInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class NvidiaGpuHealthCollector implements GpuHealthCollector {

    private static final Logger log = LoggerFactory.getLogger(NvidiaGpuHealthCollector.class);

    private static final long MIB_TO_BYTES = 1024L * 1024L;

    private static final String[] COMMAND = {"nvidia-smi", "--query-gpu=index,name,utilization.gpu,memory.total,memory.used,memory.free", "--format=csv,noheader,nounits"};

    private static final String[] AVAILABILITY_COMMAND = {"nvidia-smi", "--query-gpu=index", "--format=csv,noheader"};

    public static boolean isAvailable() {
        Process process = null;

        try {
            process = new ProcessBuilder(AVAILABILITY_COMMAND).redirectErrorStream(true).start();

            boolean completed = process.waitFor(2, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                return false;
            }

            return process.exitValue() == 0;

        } catch (IOException e) {
            return false;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;

        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    @Override
    public List<GpuInfo> getGpuInfo() {
        Process process = null;

        try {
            process = new ProcessBuilder(COMMAND).redirectErrorStream(true).start();

            boolean completed = process.waitFor(3, TimeUnit.SECONDS);

            if (!completed) {
                log.warn("nvidia-smi command timed out.");

                process.destroyForcibly();
                return List.of();
            }

            if (process.exitValue() != 0) {
                log.debug("nvidia-smi command failed. exitCode={}", process.exitValue());

                return List.of();
            }

            List<GpuInfo> gpus = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;

                while ((line = reader.readLine()) != null) {
                    GpuInfo gpuInfo = parse(line);

                    if (gpuInfo != null) gpus.add(gpuInfo);
                }
            }

            log.debug("NVIDIA GPU information collected. gpuCount={}", gpus.size());

            return gpus;

        } catch (IOException e) {
            /*
             * CPU-only 노드이거나 nvidia-smi가 설치되지 않은 환경에서는
             * 정상적으로 발생할 수 있으므로 debug 레벨로 처리한다.
             */
            log.debug("nvidia-smi is not available. NVIDIA GPU information will not be collected.");

            return List.of();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            log.warn("Interrupted while collecting NVIDIA GPU information.");

            return List.of();

        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private GpuInfo parse(String line) {
        String[] values = line.split(",");

        if (values.length != 6) {
            log.warn("Unexpected nvidia-smi output format. output={}", line);

            return null;
        }

        try {
            int index = Integer.parseInt(values[0].trim());
            String name = values[1].trim();
            double usage = Double.parseDouble(values[2].trim());

            long totalMemory = Long.parseLong(values[3].trim()) * MIB_TO_BYTES;

            long usedMemory = Long.parseLong(values[4].trim()) * MIB_TO_BYTES;

            long availableMemory = Long.parseLong(values[5].trim()) * MIB_TO_BYTES;

            return new GpuInfo(index, name, usage, totalMemory, usedMemory, availableMemory);

        } catch (NumberFormatException e) {
            log.warn("Failed to parse nvidia-smi output. output={}", line, e);
            return null;
        }
    }
}
