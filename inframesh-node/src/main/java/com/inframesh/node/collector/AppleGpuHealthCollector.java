package com.inframesh.node.collector;

import com.inframesh.node.dto.NodeHealthResponse.GpuInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AppleGpuHealthCollector implements GpuHealthCollector {

    private static final Logger log = LoggerFactory.getLogger(AppleGpuHealthCollector.class);

    private static final String[] COMMAND = {"ioreg", "-r", "-d", "1", "-c", "IOAccelerator"};

    private static final Pattern UTILIZATION_PATTERN = Pattern.compile("\"Device Utilization %\"\\s*=\\s*(\\d+)");

    private final SystemHealthCollector systemHealthCollector;

    public AppleGpuHealthCollector(SystemHealthCollector systemHealthCollector) {
        this.systemHealthCollector = systemHealthCollector;
    }

    public static boolean isAvailable() {
        String osName = System.getProperty("os.name", "");
        String osArch = System.getProperty("os.arch", "");

        return osName.toLowerCase(Locale.ROOT).contains("mac") && "aarch64".equals(osArch);
    }

    @Override
    public List<GpuInfo> getGpuInfo() {
        String name = systemHealthCollector.getCpuInfo().name() + " GPU";
        Double usage = resolveUsage();

        return List.of(new GpuInfo(0, name, usage, null, null, null));
    }

    private Double resolveUsage() {
        Process process = null;

        try {
            process = new ProcessBuilder(COMMAND).redirectErrorStream(true).start();

            boolean completed = process.waitFor(3, TimeUnit.SECONDS);

            if (!completed) {
                log.warn("ioreg command timed out.");

                process.destroyForcibly();
                return null;
            }

            if (process.exitValue() != 0) {
                log.debug("ioreg command failed. exitCode={}", process.exitValue());

                return null;
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            return parseUtilization(output);

        } catch (IOException e) {
            /*
             * ioreg가 없거나 IOAccelerator 항목이 없는 환경에서는
             * 정상적으로 발생할 수 있으므로 debug 레벨로 처리한다.
             */
            log.debug("ioreg is not available. Apple GPU utilization will not be collected.");

            return null;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            log.warn("Interrupted while collecting Apple GPU utilization.");

            return null;

        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private Double parseUtilization(String output) {
        Matcher matcher = UTILIZATION_PATTERN.matcher(output);

        if (!matcher.find()) {
            log.debug("Unable to find GPU utilization in ioreg output.");

            return null;
        }

        try {
            return Double.parseDouble(matcher.group(1));

        } catch (NumberFormatException e) {
            log.warn("Failed to parse ioreg GPU utilization. value={}", matcher.group(1), e);

            return null;
        }
    }
}
