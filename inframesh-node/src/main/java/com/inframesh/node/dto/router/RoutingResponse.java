package com.inframesh.node.dto.router;

/**
 * Result of a {@link RoutingRequest}: the id of the Worker the Router selected.
 * <p>
 * The caller (Console) must not trust this value blindly — it should be
 * validated against the {@link WorkerCandidate} list that was sent, since the
 * Router only chooses among the candidates it was given.
 */
public record RoutingResponse(
        Long workerId
) {
}
