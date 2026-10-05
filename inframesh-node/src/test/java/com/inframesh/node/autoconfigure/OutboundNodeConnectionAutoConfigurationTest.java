package com.inframesh.node.autoconfigure;

import com.inframesh.node.connection.NodeConnectionProperties;
import com.inframesh.node.connection.NodeRequestHandler;
import com.inframesh.node.connection.NodeStreamRequestHandler;
import com.inframesh.node.connection.OutboundNodeConnection;
import com.inframesh.node.connection.WebSocketOutboundNodeConnection;
import com.inframesh.node.dto.worker.WorkerRequest;
import com.inframesh.node.monitor.ActiveRequestCounter;
import com.inframesh.node.service.NodeHealthService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundNodeConnectionAutoConfigurationTest {

    // A loopback address with nothing listening: the bean is a SmartLifecycle that auto-starts on
    // context refresh, so this keeps the (expected, backgrounded, gracefully-handled) failed
    // connect attempt off the real network.
    private static final String[] OUTBOUND_PROPERTIES = {
            "inframesh.node.connection-mode=OUTBOUND",
            "inframesh.node.console-url=http://127.0.0.1:1",
            "inframesh.node.node-id=1b4e28ba-2fa1-11d2-883f-0016d3cca427",
            "inframesh.node.credential=test-credential"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboundNodeConnectionAutoConfiguration.class));

    @Test
    void directMode_doesNotCreateOutboundConnectionBean() {
        // connection-mode absent entirely, matching every existing DIRECT node's config.
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(OutboundNodeConnection.class);
            assertThat(context).doesNotHaveBean(NodeConnectionProperties.class);
        });
    }

    @Test
    void explicitDirectMode_doesNotCreateOutboundConnectionBean() {
        contextRunner
                .withPropertyValues("inframesh.node.connection-mode=DIRECT")
                .run(context -> assertThat(context).doesNotHaveBean(OutboundNodeConnection.class));
    }

    @Test
    void outboundMode_createsOutboundConnectionBeanWithBoundProperties() {
        contextRunner
                .withPropertyValues(OUTBOUND_PROPERTIES)
                .withPropertyValues(
                        "inframesh.node.outbound.heartbeat-interval=15s",
                        "inframesh.node.outbound.health-interval=45s",
                        "inframesh.node.outbound.reconnect.initial-delay=2s",
                        "inframesh.node.outbound.reconnect.max-delay=45s")
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboundNodeConnection.class);
                    assertThat(context.getBean(OutboundNodeConnection.class)).isInstanceOf(WebSocketOutboundNodeConnection.class);

                    NodeConnectionProperties properties = context.getBean(NodeConnectionProperties.class);
                    assertThat(properties.getConsoleUrl()).isEqualTo("http://127.0.0.1:1");
                    assertThat(properties.getNodeId().toString()).isEqualTo("1b4e28ba-2fa1-11d2-883f-0016d3cca427");
                    assertThat(properties.getCredential()).isEqualTo("test-credential");
                    assertThat(properties.getOutbound().getHeartbeatInterval().toSeconds()).isEqualTo(15);
                    assertThat(properties.getOutbound().getHealthInterval().toSeconds()).isEqualTo(45);
                    assertThat(properties.getOutbound().getReconnect().getInitialDelay().toSeconds()).isEqualTo(2);
                    assertThat(properties.getOutbound().getReconnect().getMaxDelay().toSeconds()).isEqualTo(45);
                });
    }

    @Test
    void outboundMode_appliesDefaultsWhenOptionalPropertiesOmitted() {
        contextRunner
                .withPropertyValues(OUTBOUND_PROPERTIES)
                .run(context -> {
                    NodeConnectionProperties properties = context.getBean(NodeConnectionProperties.class);
                    assertThat(properties.getOutbound().getHeartbeatInterval().toSeconds()).isEqualTo(10);
                    assertThat(properties.getOutbound().getHealthInterval().toSeconds()).isEqualTo(30);
                    assertThat(properties.getOutbound().getReconnect().getInitialDelay().toSeconds()).isEqualTo(1);
                    assertThat(properties.getOutbound().getReconnect().getMaxDelay().toSeconds()).isEqualTo(30);
                });
    }

    @Test
    void outboundMode_withRequestHandlerBean_startsConnection() {
        contextRunner
                .withPropertyValues(OUTBOUND_PROPERTIES)
                .withBean(NodeRequestHandler.class, () -> NodeRequestHandler.of(String.class, request -> request))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(WebSocketOutboundNodeConnection.class).isRunning()).isTrue();
                });
    }

    @Test
    void outboundMode_withNodeAutoConfiguration_startsWithNodeHealthServiceAsHealthSource() {
        // NodeAutoConfiguration provides the same NodeHealthService behind DIRECT's /api/v1/health.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        NodeAutoConfiguration.class, OutboundNodeConnectionAutoConfiguration.class))
                .withPropertyValues(OUTBOUND_PROPERTIES)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(NodeHealthService.class);
                    assertThat(context.getBean(WebSocketOutboundNodeConnection.class).isRunning()).isTrue();
                });
    }

    @Test
    void outboundMode_withStreamRequestHandlerBean_startsConnection() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        NodeAutoConfiguration.class, OutboundNodeConnectionAutoConfiguration.class))
                .withPropertyValues(OUTBOUND_PROPERTIES)
                .withBean(NodeStreamRequestHandler.class,
                        () -> NodeStreamRequestHandler.of(WorkerRequest.class, request -> Flux.<String>empty()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ActiveRequestCounter.class);
                    assertThat(context.getBean(WebSocketOutboundNodeConnection.class).isRunning()).isTrue();
                });
    }

    @Test
    void userDefinedConnection_backsOffAutoConfiguredOne() {
        OutboundNodeConnection custom = new OutboundNodeConnection() {
            @Override
            public void connect() {
            }

            @Override
            public void disconnect() {
            }

            @Override
            public boolean isConnected() {
                return false;
            }

            @Override
            public void send(com.inframesh.node.dto.connection.NodeEnvelope<?> message) {
            }
        };
        contextRunner
                .withPropertyValues(OUTBOUND_PROPERTIES)
                .withBean(OutboundNodeConnection.class, () -> custom)
                .run(context -> assertThat(context.getBean(OutboundNodeConnection.class)).isSameAs(custom));
    }
}
