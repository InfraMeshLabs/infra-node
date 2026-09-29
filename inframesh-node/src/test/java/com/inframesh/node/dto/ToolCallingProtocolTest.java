package com.inframesh.node.dto;

import com.inframesh.node.dto.router.RoutingHints;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.worker.WorkerRequest;
import com.inframesh.node.enums.ChatRole;
import com.inframesh.node.enums.FinishReason;
import com.inframesh.node.enums.ToolChoiceMode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallingProtocolTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void legacyRoutingRequestWithoutToolsStillDeserializes() {
        String json = """
                {
                  "messages": [
                    {"role": "USER", "content": "hello"}
                  ],
                  "options": {"temperature": 0.7}
                }
                """;

        RoutingRequest request = mapper.readValue(json, RoutingRequest.class);

        assertThat(request.messages()).hasSize(1);
        assertThat(request.messages().get(0).role()).isEqualTo(ChatRole.USER);
        assertThat(request.messages().get(0).content()).isEqualTo("hello");
        assertThat(request.messages().get(0).toolCalls()).isEmpty();
        assertThat(request.tools()).isEmpty();
        assertThat(request.toolChoice()).isNull();
        assertThat(request.routing()).isNull();
    }

    @Test
    void legacyWorkerRequestConstructorStillCompiles() {
        WorkerRequest request = new WorkerRequest(
                List.of(ChatMessage.user("hi")),
                new ChatOptions(0.5, 100, 1.0)
        );

        assertThat(request.tools()).isEmpty();
        assertThat(request.toolChoice()).isNull();
    }

    @Test
    void legacyChatResponseConstructorStillCompiles() {
        ChatResponse response = new ChatResponse("hello there", new Usage(10L, 5L, 15L));

        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.finishReason()).isNull();
        assertThat(response.usage().totalTokens()).isEqualTo(15L);
    }

    @Test
    void requestWithToolDefinitionsSerializesAndDeserializes() {
        JsonNode schema = mapper.readTree("""
                {
                  "type": "object",
                  "properties": {"path": {"type": "string"}},
                  "required": ["path"]
                }
                """);

        ToolDefinition readFile = new ToolDefinition("read_file", "Read a file", schema);

        RoutingRequest request = new RoutingRequest(
                List.of(ChatMessage.user("read src/Main.java")),
                new ChatOptions(0.2, 512, null),
                new RoutingHints("gpt-oss", null),
                List.of(readFile),
                ToolChoice.AUTO
        );

        String json = mapper.writeValueAsString(request);
        RoutingRequest roundTripped = mapper.readValue(json, RoutingRequest.class);

        assertThat(roundTripped.tools()).hasSize(1);
        ToolDefinition roundTrippedTool = roundTripped.tools().get(0);
        assertThat(roundTrippedTool.name()).isEqualTo("read_file");
        assertThat(roundTrippedTool.parameters().get("required").get(0).asString()).isEqualTo("path");
        assertThat(roundTripped.toolChoice().mode()).isEqualTo(ToolChoiceMode.AUTO);
    }

    @Test
    void toolChoiceCanTargetASpecificTool() {
        ToolChoice choice = ToolChoice.tool("read_file");

        String json = mapper.writeValueAsString(choice);
        ToolChoice roundTripped = mapper.readValue(json, ToolChoice.class);

        assertThat(roundTripped.mode()).isEqualTo(ToolChoiceMode.TOOL);
        assertThat(roundTripped.toolName()).isEqualTo("read_file");
    }

    @Test
    void assistantToolCallResponseSerializesAndDeserializes() {
        JsonNode arguments = mapper.readTree("{\"path\":\"src/Main.java\"}");
        ToolCall toolCall = new ToolCall("call_123", "read_file", arguments);

        ChatResponse response = new ChatResponse(
                null,
                List.of(toolCall),
                FinishReason.TOOL_CALLS,
                new Usage(20L, 0L, 20L)
        );

        String json = mapper.writeValueAsString(response);
        ChatResponse roundTripped = mapper.readValue(json, ChatResponse.class);

        assertThat(roundTripped.message()).isNull();
        assertThat(roundTripped.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(roundTripped.toolCalls()).hasSize(1);

        ToolCall roundTrippedCall = roundTripped.toolCalls().get(0);
        assertThat(roundTrippedCall.id()).isEqualTo("call_123");
        assertThat(roundTrippedCall.name()).isEqualTo("read_file");
        assertThat(roundTrippedCall.arguments().get("path").asString()).isEqualTo("src/Main.java");
    }

    @Test
    void toolResultMessageSerializesAndDeserializes() {
        ChatMessage toolResult = ChatMessage.toolResult("call_123", "{\"lines\": 42}");

        String json = mapper.writeValueAsString(toolResult);
        ChatMessage roundTripped = mapper.readValue(json, ChatMessage.class);

        assertThat(roundTripped.role()).isEqualTo(ChatRole.TOOL);
        assertThat(roundTripped.toolCallId()).isEqualTo("call_123");
        assertThat(roundTripped.content()).isEqualTo("{\"lines\": 42}");
        assertThat(roundTripped.toolCalls()).isEmpty();
    }

    @Test
    void streamingResponseCarriesToolCallDeltasAndTerminalMetadata() {
        ChatStreamResponse textChunk = new ChatStreamResponse("partial answer");
        assertThat(textChunk.toolCallDeltas()).isEmpty();
        assertThat(textChunk.finishReason()).isNull();

        ChatStreamResponse toolCallChunk = new ChatStreamResponse(
                null,
                List.of(new ToolCallDelta(0, "call_123", "read_file", "{\"path\":")),
                null,
                null
        );

        String json = mapper.writeValueAsString(toolCallChunk);
        ChatStreamResponse roundTripped = mapper.readValue(json, ChatStreamResponse.class);

        assertThat(roundTripped.toolCallDeltas()).hasSize(1);
        assertThat(roundTripped.toolCallDeltas().get(0).id()).isEqualTo("call_123");
        assertThat(roundTripped.toolCallDeltas().get(0).argumentsDelta()).isEqualTo("{\"path\":");

        ChatStreamResponse finalChunk = new ChatStreamResponse(
                null,
                List.of(new ToolCallDelta(0, null, null, "\"src/Main.java\"}")),
                FinishReason.TOOL_CALLS,
                new Usage(30L, 8L, 38L)
        );

        assertThat(finalChunk.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(finalChunk.usage().totalTokens()).isEqualTo(38L);
    }
}
