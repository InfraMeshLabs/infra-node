package com.inframesh.node.dto;

import com.inframesh.node.dto.connection.NodeEnvelope;
import com.inframesh.node.dto.connection.NodeError;
import com.inframesh.node.dto.connection.NodeStreamChunk;
import com.inframesh.node.dto.connection.NodeStreamComplete;
import com.inframesh.node.dto.worker.WorkerRequest;
import com.inframesh.node.enums.ChatRole;
import com.inframesh.node.enums.FinishReason;
import com.inframesh.node.enums.NodeMessageType;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamingProtocolTest {

    private static final UUID NODE_ID = UUID.fromString("e56f73e5-15ad-4cc0-b24c-f58f90c70f42");
    private static final Instant TIMESTAMP = Instant.parse("2026-09-30T05:30:00Z");

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void streamRequestCarriesWorkerRequestAsIs() {
        WorkerRequest request = new WorkerRequest("session-1",
                List.of(new ChatMessage(ChatRole.USER, "hello")), null, List.of(), null, true);
        NodeEnvelope<WorkerRequest> envelope =
                new NodeEnvelope<>("m-1", NodeMessageType.STREAM_REQUEST, NODE_ID, TIMESTAMP, "req-1", request);

        String json = mapper.writeValueAsString(envelope);

        assertThat(json).contains("\"type\":\"STREAM_REQUEST\"").contains("\"requestId\":\"req-1\"");
        // Same shape as a non-streaming REQUEST payload: no streaming-specific wrapper.
        assertThat(mapper.readTree(json).get("payload")).isEqualTo(mapper.readTree(mapper.writeValueAsString(request)));
        assertThat(mapper.readValue(json, new TypeReference<NodeEnvelope<WorkerRequest>>() {
        })).isEqualTo(envelope);
    }

    @Test
    void streamChunkRoundTripsWithSequenceAndChatStreamResponse() {
        NodeStreamChunk<ChatStreamResponse> chunk = new NodeStreamChunk<>(3,
                new ChatStreamResponse("Hel", List.of(new ToolCallDelta(0, "call-1", "search", "{\"q\"")), null, null));
        NodeEnvelope<NodeStreamChunk<ChatStreamResponse>> envelope =
                new NodeEnvelope<>("m-2", NodeMessageType.STREAM_CHUNK, NODE_ID, TIMESTAMP, "req-1", chunk);

        String json = mapper.writeValueAsString(envelope);

        assertThat(json).contains("\"type\":\"STREAM_CHUNK\"")
                .contains("\"payload\":{\"sequence\":3,\"data\":{\"content\":\"Hel\"");
        assertThat(mapper.readValue(json, new TypeReference<NodeEnvelope<NodeStreamChunk<ChatStreamResponse>>>() {
        })).isEqualTo(envelope);
    }

    @Test
    void streamChunkRejectsNegativeSequence() {
        assertThatThrownBy(() -> new NodeStreamChunk<>(-1, "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void streamCompleteRoundTrips() {
        NodeEnvelope<NodeStreamComplete> envelope = new NodeEnvelope<>(
                "m-3", NodeMessageType.STREAM_COMPLETE, NODE_ID, TIMESTAMP, "req-1", new NodeStreamComplete(5));

        String json = mapper.writeValueAsString(envelope);

        assertThat(json).contains("\"type\":\"STREAM_COMPLETE\"").contains("\"payload\":{\"chunkCount\":5}");
        assertThat(mapper.readValue(json, new TypeReference<NodeEnvelope<NodeStreamComplete>>() {
        })).isEqualTo(envelope);
    }

    @Test
    void streamErrorRoundTrips() {
        NodeEnvelope<NodeError> envelope = new NodeEnvelope<>("m-4", NodeMessageType.STREAM_ERROR, NODE_ID, TIMESTAMP,
                "req-1", new NodeError("RUNTIME_UNAVAILABLE", "Model runtime is not reachable"));

        String json = mapper.writeValueAsString(envelope);

        assertThat(json).contains("\"type\":\"STREAM_ERROR\"").contains("\"requestId\":\"req-1\"")
                .contains("\"payload\":{\"code\":\"RUNTIME_UNAVAILABLE\",\"message\":\"Model runtime is not reachable\"}");
        assertThat(mapper.readValue(json, new TypeReference<NodeEnvelope<NodeError>>() {
        })).isEqualTo(envelope);
    }

    @Test
    void errorPayloadWithoutCode_stillDeserializes() {
        String json = """
                {"messageId": "m-5", "type": "ERROR", "requestId": "req-1", "payload": {"message": "boom"}}
                """;

        NodeEnvelope<NodeError> envelope = mapper.readValue(json, new TypeReference<NodeEnvelope<NodeError>>() {
        });

        assertThat(envelope.payload()).isEqualTo(new NodeError(null, "boom"));
    }

    @Test
    void cancelCarriesTargetRequestIdWithoutPayload() {
        NodeEnvelope<Void> envelope = new NodeEnvelope<>("m-6", NodeMessageType.CANCEL, NODE_ID, TIMESTAMP, "req-1", null);

        String json = mapper.writeValueAsString(envelope);

        assertThat(json).contains("\"type\":\"CANCEL\"").contains("\"requestId\":\"req-1\"");
        NodeEnvelope<JsonNode> restored = mapper.readValue(json, new TypeReference<NodeEnvelope<JsonNode>>() {
        });
        assertThat(restored.type()).isEqualTo(NodeMessageType.CANCEL);
        assertThat(restored.requestId()).isEqualTo("req-1");
        assertThat(restored.payload() == null || restored.payload().isNull()).isTrue();
    }

    @Test
    void streamLifecycle_sharesRequestIdWithIndependentMessageIdsAndOrderedSequence() {
        List<NodeEnvelope<?>> lifecycle = new ArrayList<>();
        lifecycle.add(NodeEnvelope.create(NodeMessageType.STREAM_REQUEST, NODE_ID, "req-1",
                new WorkerRequest(List.of(new ChatMessage(ChatRole.USER, "hi")), null)));
        List<String> parts = List.of("Hel", "lo", "!");
        for (int i = 0; i < parts.size(); i++) {
            ChatStreamResponse data = i == parts.size() - 1
                    ? new ChatStreamResponse(parts.get(i), List.of(), FinishReason.STOP, null)
                    : new ChatStreamResponse(parts.get(i));
            lifecycle.add(NodeEnvelope.create(NodeMessageType.STREAM_CHUNK, NODE_ID, "req-1", new NodeStreamChunk<>(i, data)));
        }
        lifecycle.add(NodeEnvelope.create(NodeMessageType.STREAM_COMPLETE, NODE_ID, "req-1", new NodeStreamComplete(parts.size())));

        List<JsonNode> wire = lifecycle.stream().map(e -> mapper.readTree(mapper.writeValueAsString(e))).toList();

        assertThat(wire).allSatisfy(node -> assertThat(node.get("requestId").asString()).isEqualTo("req-1"));
        assertThat(new HashSet<>(wire.stream().map(node -> node.get("messageId").asString()).toList())).hasSize(wire.size());
        assertThat(wire.stream().filter(node -> node.get("type").asString().equals("STREAM_CHUNK"))
                .map(node -> node.get("payload").get("sequence").asLong()).toList()).containsExactly(0L, 1L, 2L);
        assertThat(wire.get(wire.size() - 1).get("payload").get("chunkCount").asLong()).isEqualTo(3L);
    }

    @Test
    void existingRequestResponseShapeIsUnchanged() {
        NodeEnvelope<Map<String, String>> response = new NodeEnvelope<>(
                "m-7", NodeMessageType.RESPONSE, NODE_ID, TIMESTAMP, "req-1", Map.of("message", "ok"));

        String json = mapper.writeValueAsString(response);

        assertThat(json).isEqualTo("{\"messageId\":\"m-7\",\"type\":\"RESPONSE\",\"nodeId\":\"" + NODE_ID
                + "\",\"timestamp\":\"2026-09-30T05:30:00Z\",\"requestId\":\"req-1\",\"payload\":{\"message\":\"ok\"}}");
    }
}
