package com.inframesh.node.connection;

import com.inframesh.node.dto.connection.NodeEnvelope;
import tools.jackson.databind.JsonNode;

/**
 * Generic receiver for inbound envelopes other than REQUEST (which is served by
 * {@link NodeRequestHandler}): acknowledgements, CANCEL, and - unless a
 * {@link NodeStreamRequestHandler} serves it - STREAM_REQUEST.
 *
 * Without a {@link NodeStreamRequestHandler} a node may serve streaming here
 * itself: on STREAM_REQUEST it deserializes the payload (a {@code WorkerRequest}),
 * runs its streaming inference, and sends STREAM_CHUNK... then STREAM_COMPLETE
 * or STREAM_ERROR through {@link OutboundNodeConnection#send}, each with the
 * STREAM_REQUEST's {@code requestId}; on CANCEL it stops the inference with
 * that requestId. The SDK holds no state for such a stream and does not count
 * it as an active request - prefer {@link NodeStreamRequestHandler}.
 *
 * Every registered handler receives every such envelope, with the payload left
 * as raw JSON - the connection SDK does not interpret message semantics.
 * Handlers are invoked off the WebSocket listener thread and must not assume
 * ordering across messages (a CANCEL may be handled before or while its
 * STREAM_REQUEST is). Each invocation has its own thread, so a handler may
 * block for the whole stream.
 */
@FunctionalInterface
public interface NodeMessageHandler {

    void handle(NodeEnvelope<JsonNode> envelope);
}
