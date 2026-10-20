package com.inframesh.node.dto;

import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.WorkerCandidate;
import com.inframesh.node.dto.worker.WorkerRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityContextProtocolTest {

    private static final UUID TEAM = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEMBER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID NODE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void workerRequestRoundTripsIdentity() {
        WorkerRequest request = new WorkerRequest("s-1", List.of(ChatMessage.user("hi")), null, List.of(), null, null, TEAM, MEMBER);

        WorkerRequest read = mapper.readValue(mapper.writeValueAsString(request), WorkerRequest.class);

        assertThat(read.teamUuid()).isEqualTo(TEAM);
        assertThat(read.memberUuid()).isEqualTo(MEMBER);
        assertThat(read.sessionId()).isEqualTo("s-1");
    }

    @Test
    void routingRequestRoundTripsIdentityAndCandidateNodeId() {
        RoutingRequest request = new RoutingRequest("s-1", List.of(ChatMessage.user("hi")), null, null, List.of(), null,
                List.of(candidate(7L, NODE)), TEAM, MEMBER);

        RoutingRequest read = mapper.readValue(mapper.writeValueAsString(request), RoutingRequest.class);

        assertThat(read.teamUuid()).isEqualTo(TEAM);
        assertThat(read.memberUuid()).isEqualTo(MEMBER);
        assertThat(read.workers()).singleElement().satisfies(worker -> {
            assertThat(worker.workerId()).isEqualTo(7L);
            assertThat(worker.nodeId()).isEqualTo(NODE);
        });
    }

    // An older Console sends none of the new fields.
    @Test
    void missingIdentityFieldsDeserializeToNull() {
        String json = """
                {"sessionId": "s-1", "messages": [{"role": "USER", "content": "hi"}],
                 "workers": [{"workerId": 7, "name": "w"}]}
                """;

        WorkerRequest worker = mapper.readValue(json, WorkerRequest.class);
        RoutingRequest routing = mapper.readValue(json, RoutingRequest.class);

        assertThat(worker.teamUuid()).isNull();
        assertThat(worker.memberUuid()).isNull();
        assertThat(routing.teamUuid()).isNull();
        assertThat(routing.memberUuid()).isNull();
        assertThat(routing.workers()).singleElement().satisfies(candidate -> assertThat(candidate.nodeId()).isNull());
    }

    // An older Node, which does not know the new fields, must still read a newer Console's request.
    @Test
    void unknownFieldsAreIgnoredByDefaultMapper() {
        String json = """
                {"sessionId": "s-1", "messages": [{"role": "USER", "content": "hi"}],
                 "teamUuid": "%s", "memberUuid": "%s", "someFutureField": {"x": 1}}
                """.formatted(TEAM, MEMBER);

        WorkerRequest request = JsonMapper.shared().readValue(json, WorkerRequest.class);

        assertThat(request.sessionId()).isEqualTo("s-1");
        assertThat(request.messages()).hasSize(1);
    }

    @Test
    void legacyConstructorsLeaveIdentityNull() {
        WorkerRequest worker = new WorkerRequest("s-1", List.of(ChatMessage.user("hi")), null, List.of(), null, null);
        RoutingRequest routing = new RoutingRequest("s-1", List.of(ChatMessage.user("hi")), null, null, List.of(), null, List.of());
        WorkerCandidate candidate = new WorkerCandidate(7L, "w", null, List.of(), null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(worker.teamUuid()).isNull();
        assertThat(worker.memberUuid()).isNull();
        assertThat(routing.teamUuid()).isNull();
        assertThat(routing.memberUuid()).isNull();
        assertThat(candidate.nodeId()).isNull();
    }

    private static WorkerCandidate candidate(Long workerId, UUID nodeId) {
        return new WorkerCandidate(workerId, "w", null, List.of(), null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, nodeId);
    }
}
