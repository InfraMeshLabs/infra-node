package com.inframesh.node.dto;

import com.inframesh.node.dto.connection.NodeEnvelope;
import com.inframesh.node.dto.registration.NodeRegistrationRequest;
import com.inframesh.node.dto.registration.NodeRegistrationResponse;
import com.inframesh.node.enums.NodeConnectionMode;
import com.inframesh.node.enums.NodeConnectionState;
import com.inframesh.node.enums.NodeMessageType;
import com.inframesh.node.enums.NodeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NodeConnectionProtocolTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    record TestPayload(String status, Integer activeRequests) {
    }

    @Test
    void registrationRequestRoundTrips() {
        NodeRegistrationRequest request = new NodeRegistrationRequest("token-123", "worker-1", NodeType.WORKER);

        String json = mapper.writeValueAsString(request);

        assertThat(json).contains("\"registrationToken\":\"token-123\"");
        assertThat(json).contains("\"nodeType\":\"WORKER\"");
        assertThat(mapper.readValue(json, NodeRegistrationRequest.class)).isEqualTo(request);
    }

    @Test
    void registrationRequestDeserializesNodeType() {
        String json = """
                {"registrationToken": "token-123", "name": "router-1", "nodeType": "ROUTER"}
                """;

        NodeRegistrationRequest request = mapper.readValue(json, NodeRegistrationRequest.class);

        assertThat(request.registrationToken()).isEqualTo("token-123");
        assertThat(request.name()).isEqualTo("router-1");
        assertThat(request.nodeType()).isEqualTo(NodeType.ROUTER);
    }

    @Test
    void registrationResponseRoundTrips() {
        UUID nodeId = UUID.fromString("0bd8106c-4c1a-4e38-a23a-2c07b75a74cc");
        NodeRegistrationResponse response = new NodeRegistrationResponse(nodeId, "credential-abc");

        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"nodeId\":\"0bd8106c-4c1a-4e38-a23a-2c07b75a74cc\"");
        assertThat(mapper.readValue(json, NodeRegistrationResponse.class)).isEqualTo(response);
    }

    @ParameterizedTest
    @EnumSource(NodeType.class)
    void nodeTypeRoundTrips(NodeType value) {
        assertRoundTrip(value, NodeType.class);
    }

    @ParameterizedTest
    @EnumSource(NodeConnectionMode.class)
    void connectionModeRoundTrips(NodeConnectionMode value) {
        assertRoundTrip(value, NodeConnectionMode.class);
    }

    @ParameterizedTest
    @EnumSource(NodeConnectionState.class)
    void connectionStateRoundTrips(NodeConnectionState value) {
        assertRoundTrip(value, NodeConnectionState.class);
    }

    @ParameterizedTest
    @EnumSource(NodeMessageType.class)
    void messageTypeRoundTrips(NodeMessageType value) {
        assertRoundTrip(value, NodeMessageType.class);
    }

    @Test
    void envelopeWithTypedPayloadRoundTrips() {
        NodeEnvelope<TestPayload> envelope = new NodeEnvelope<>(
                "req-123",
                NodeMessageType.REQUEST,
                new TestPayload("ONLINE", 3)
        );

        String json = mapper.writeValueAsString(envelope);

        assertThat(json).contains("\"requestId\":\"req-123\"");
        assertThat(json).contains("\"type\":\"REQUEST\"");
        assertThat(json).contains("\"payload\":{\"status\":\"ONLINE\",\"activeRequests\":3}");

        NodeEnvelope<TestPayload> restored = mapper.readValue(json, new TypeReference<NodeEnvelope<TestPayload>>() {
        });

        assertThat(restored).isEqualTo(envelope);
    }

    @Test
    void envelopeDeserializesUntypedPayload() {
        String json = """
                {
                  "requestId": "req-123",
                  "type": "HEARTBEAT",
                  "payload": {"status": "ONLINE"}
                }
                """;

        NodeEnvelope<Map<String, Object>> envelope = mapper.readValue(json, new TypeReference<NodeEnvelope<Map<String, Object>>>() {
        });

        assertThat(envelope.requestId()).isEqualTo("req-123");
        assertThat(envelope.type()).isEqualTo(NodeMessageType.HEARTBEAT);
        assertThat(envelope.payload()).containsEntry("status", "ONLINE");
    }

    @Test
    void envelopeWithoutPayloadDeserializesToNull() {
        String json = """
                {"requestId": "req-1", "type": "HEARTBEAT_ACK"}
                """;

        NodeEnvelope<TestPayload> envelope = mapper.readValue(json, new TypeReference<NodeEnvelope<TestPayload>>() {
        });

        assertThat(envelope.type()).isEqualTo(NodeMessageType.HEARTBEAT_ACK);
        assertThat(envelope.payload()).isNull();
    }

    private <E extends Enum<E>> void assertRoundTrip(E value, Class<E> type) {
        String json = mapper.writeValueAsString(value);

        assertThat(json).isEqualTo("\"" + value.name() + "\"");
        assertThat(mapper.readValue(json, type)).isEqualTo(value);
    }
}
