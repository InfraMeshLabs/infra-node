package com.inframesh.node.dto;

import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.worker.WorkerRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionIdProtocolTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void workerRequestDeserializesSessionId() {
        String json = """
                {
                  "sessionId": "session-123",
                  "messages": [
                    {"role": "USER", "content": "hello"}
                  ]
                }
                """;

        WorkerRequest request = mapper.readValue(json, WorkerRequest.class);

        assertThat(request.sessionId()).isEqualTo("session-123");
    }

    @Test
    void workerRequestWithoutSessionIdDeserializesToNull() {
        String json = """
                {
                  "messages": [
                    {"role": "USER", "content": "hello"}
                  ]
                }
                """;

        WorkerRequest request = mapper.readValue(json, WorkerRequest.class);

        assertThat(request.sessionId()).isNull();
    }

    @Test
    void workerRequestSerializesSessionId() {
        WorkerRequest request = new WorkerRequest(
                "session-123",
                List.of(ChatMessage.user("hi")),
                new ChatOptions(0.5, 100, 1.0),
                List.of(),
                null,
                null
        );

        String json = mapper.writeValueAsString(request);

        assertThat(json).contains("\"sessionId\":\"session-123\"");
    }

    @Test
    void legacyWorkerRequestConstructorsDefaultSessionIdToNull() {
        WorkerRequest twoArg = new WorkerRequest(
                List.of(ChatMessage.user("hi")),
                new ChatOptions(0.5, 100, 1.0)
        );
        WorkerRequest fiveArg = new WorkerRequest(
                List.of(ChatMessage.user("hi")),
                new ChatOptions(0.5, 100, 1.0),
                List.of(),
                null,
                true
        );

        assertThat(twoArg.sessionId()).isNull();
        assertThat(fiveArg.sessionId()).isNull();
    }

    @Test
    void routingRequestDeserializesSessionId() {
        String json = """
                {
                  "sessionId": "session-123",
                  "messages": [
                    {"role": "USER", "content": "hello"}
                  ]
                }
                """;

        RoutingRequest request = mapper.readValue(json, RoutingRequest.class);

        assertThat(request.sessionId()).isEqualTo("session-123");
    }

    @Test
    void routingRequestWithoutSessionIdDeserializesToNull() {
        String json = """
                {
                  "messages": [
                    {"role": "USER", "content": "hello"}
                  ]
                }
                """;

        RoutingRequest request = mapper.readValue(json, RoutingRequest.class);

        assertThat(request.sessionId()).isNull();
    }

    @Test
    void routingRequestSerializesSessionId() {
        RoutingRequest request = new RoutingRequest(
                "session-123",
                List.of(ChatMessage.user("hi")),
                new ChatOptions(0.5, 100, 1.0),
                null,
                List.of(),
                null,
                List.of()
        );

        String json = mapper.writeValueAsString(request);

        assertThat(json).contains("\"sessionId\":\"session-123\"");
    }

    @Test
    void legacyRoutingRequestConstructorsDefaultSessionIdToNull() {
        RoutingRequest threeArg = new RoutingRequest(
                List.of(ChatMessage.user("hi")),
                new ChatOptions(0.5, 100, 1.0),
                null
        );

        assertThat(threeArg.sessionId()).isNull();
    }
}
