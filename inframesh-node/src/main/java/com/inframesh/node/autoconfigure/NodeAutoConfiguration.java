package com.inframesh.node.autoconfigure;

import com.inframesh.node.collector.AppleGpuHealthCollector;
import com.inframesh.node.collector.GpuHealthCollector;
import com.inframesh.node.collector.NoopGpuHealthCollector;
import com.inframesh.node.collector.NvidiaGpuHealthCollector;
import com.inframesh.node.collector.SystemHealthCollector;
import com.inframesh.node.controller.HealthCheckController;
import com.inframesh.node.filter.NodeApiKeyFilter;
import com.inframesh.node.filter.NodeApiKeyServletFilter;
import com.inframesh.node.monitor.ActiveRequestCounter;
import com.inframesh.node.properties.NodeProperties;
import com.inframesh.node.service.NodeHealthService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@AutoConfiguration
@EnableConfigurationProperties(NodeProperties.class)
public class NodeAutoConfiguration {

    @Bean
    public SystemHealthCollector systemHealthCollector() {
        return new SystemHealthCollector();
    }

    @Bean
    public GpuHealthCollector gpuHealthCollector(SystemHealthCollector systemHealthCollector) {
        if (NvidiaGpuHealthCollector.isAvailable()) {
            return new NvidiaGpuHealthCollector();
        }

        if (AppleGpuHealthCollector.isAvailable()) {
            return new AppleGpuHealthCollector(systemHealthCollector);
        }

        return new NoopGpuHealthCollector();
    }

    @Bean
    public ActiveRequestCounter activeRequestCounter() {
        return new ActiveRequestCounter();
    }

    @Bean
    public NodeHealthService nodeHealthService(SystemHealthCollector systemHealthCollector, GpuHealthCollector gpuHealthCollector, ActiveRequestCounter activeRequestCounter) {
        return new NodeHealthService(systemHealthCollector, gpuHealthCollector, activeRequestCounter);
    }

    @Bean
    public HealthCheckController healthCheckController(NodeHealthService nodeHealthService) {
        return new HealthCheckController(nodeHealthService);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class ServletSecurityConfiguration {

        @Bean
        public NodeApiKeyServletFilter nodeApiKeyServletFilter(NodeProperties properties) {
            return new NodeApiKeyServletFilter(properties);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    static class ReactiveSecurityConfiguration {

        @Bean
        public NodeApiKeyFilter nodeApiKeyFilter(NodeProperties properties) {
            return new NodeApiKeyFilter(properties);
        }
    }
}
