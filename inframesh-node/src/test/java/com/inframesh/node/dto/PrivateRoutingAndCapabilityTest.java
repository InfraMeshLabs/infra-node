package com.inframesh.node.dto;

import com.inframesh.node.dto.router.ModelInfo;
import com.inframesh.node.dto.router.RoutingHints;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.WorkerCandidate;
import com.inframesh.node.dto.worker.WorkerRequest;
import com.inframesh.node.enums.ExecutionLocation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrivateRoutingAndCapabilityTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private WorkerCandidate candidate(List<ModelInfo> models) {
        return new WorkerCandidate(
                1L, "worker-a", "worker",
                models, null,
                null, null,
                0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0L,
                null, null,
                null, null,
                null, null,
                null, null
        );
    }

    @Test
    void executionLocationLocalSerializesAndDeserializes() {
        ModelInfo model = new ModelInfo("OLLAMA", "qwen", ExecutionLocation.LOCAL);

        String json = mapper.writeValueAsString(model);
        ModelInfo roundTripped = mapper.readValue(json, ModelInfo.class);

        assertThat(json).contains("\"LOCAL\"");
        assertThat(roundTripped.executionLocation()).isEqualTo(ExecutionLocation.LOCAL);
    }

    @Test
    void executionLocationExternalSerializesAndDeserializes() {
        ModelInfo model = new ModelInfo("OPENAI", "gpt", ExecutionLocation.EXTERNAL);

        String json = mapper.writeValueAsString(model);
        ModelInfo roundTripped = mapper.readValue(json, ModelInfo.class);

        assertThat(json).contains("\"EXTERNAL\"");
        assertThat(roundTripped.executionLocation()).isEqualTo(ExecutionLocation.EXTERNAL);
    }

    @Test
    void workerCandidateWithSingleModel() {
        WorkerCandidate worker = candidate(List.of(new ModelInfo("OLLAMA", "qwen", ExecutionLocation.LOCAL)));

        String json = mapper.writeValueAsString(worker);
        WorkerCandidate roundTripped = mapper.readValue(json, WorkerCandidate.class);

        assertThat(roundTripped.models()).hasSize(1);
        assertThat(roundTripped.models().get(0).modelName()).isEqualTo("qwen");
        assertThat(roundTripped.models().get(0).executionLocation()).isEqualTo(ExecutionLocation.LOCAL);
    }

    @Test
    void workerCandidateWithMultipleModels() {
        WorkerCandidate worker = candidate(List.of(
                new ModelInfo("OLLAMA", "qwen", ExecutionLocation.LOCAL),
                new ModelInfo("OLLAMA", "llama", ExecutionLocation.LOCAL)
        ));

        String json = mapper.writeValueAsString(worker);
        WorkerCandidate roundTripped = mapper.readValue(json, WorkerCandidate.class);

        assertThat(roundTripped.models()).hasSize(2);
        assertThat(roundTripped.models()).extracting(ModelInfo::modelName)
                .containsExactly("qwen", "llama");
    }

    @Test
    void workerCandidateWithMixedLocalAndExternalModels() {
        WorkerCandidate worker = candidate(List.of(
                new ModelInfo("OLLAMA", "qwen", ExecutionLocation.LOCAL),
                new ModelInfo("OPENAI", "gpt", ExecutionLocation.EXTERNAL)
        ));

        String json = mapper.writeValueAsString(worker);
        WorkerCandidate roundTripped = mapper.readValue(json, WorkerCandidate.class);

        assertThat(roundTripped.models())
                .extracting(ModelInfo::executionLocation)
                .containsExactlyInAnyOrder(ExecutionLocation.LOCAL, ExecutionLocation.EXTERNAL);
    }

    @Test
    void routingHintsPrivateDataTrue() {
        RoutingHints hints = new RoutingHints(null, null, List.of(), null, null, true);

        String json = mapper.writeValueAsString(hints);
        RoutingHints roundTripped = mapper.readValue(json, RoutingHints.class);

        assertThat(roundTripped.privateData()).isTrue();
        assertThat(roundTripped.isPrivateData()).isTrue();
    }

    @Test
    void routingHintsPrivateDataFalse() {
        RoutingHints hints = new RoutingHints(null, null, List.of(), null, null, false);

        String json = mapper.writeValueAsString(hints);
        RoutingHints roundTripped = mapper.readValue(json, RoutingHints.class);

        assertThat(roundTripped.privateData()).isFalse();
        assertThat(roundTripped.isPrivateData()).isFalse();
    }

    @Test
    void routingHintsPrivateDataMissingDefaultsToFalse() {
        String json = """
                {
                  "preferredModel": "gpt-oss"
                }
                """;

        RoutingHints hints = mapper.readValue(json, RoutingHints.class);

        assertThat(hints.privateData()).isNull();
        assertThat(hints.isPrivateData()).isFalse();
    }

    @Test
    void routingHintsPrivateDataExplicitNullBehavesLikeFalse() {
        RoutingHints hints = new RoutingHints(null, null, List.of(), null, null, null);

        assertThat(hints.privateData()).isNull();
        assertThat(hints.isPrivateData()).isFalse();
    }

    @Test
    void legacyRequestWithoutPrivateDataStillDeserializes() {
        String json = """
                {
                  "messages": [
                    {"role": "USER", "content": "hello"}
                  ]
                }
                """;

        RoutingRequest request = mapper.readValue(json, RoutingRequest.class);

        assertThat(request.messages()).hasSize(1);
        assertThat(request.routing()).isNull();
        assertThat(request.workers()).isEmpty();
    }

    @Test
    void workerRequestCarriesPrivateDataThroughFromRouting() {
        WorkerRequest request = new WorkerRequest(
                List.of(ChatMessage.user("hi")),
                new ChatOptions(0.5, 100, 1.0),
                List.of(),
                null,
                true
        );

        String json = mapper.writeValueAsString(request);
        WorkerRequest roundTripped = mapper.readValue(json, WorkerRequest.class);

        assertThat(roundTripped.privateData()).isTrue();
        assertThat(roundTripped.isPrivateData()).isTrue();
    }

    @Test
    void legacyWorkerRequestConstructorDefaultsPrivateDataToFalse() {
        WorkerRequest request = new WorkerRequest(
                List.of(ChatMessage.user("hi")),
                new ChatOptions(0.5, 100, 1.0)
        );

        assertThat(request.privateData()).isNull();
        assertThat(request.isPrivateData()).isFalse();
    }
}
