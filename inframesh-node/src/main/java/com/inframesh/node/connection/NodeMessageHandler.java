package com.inframesh.node.connection;

import com.inframesh.node.dto.connection.NodeEnvelope;
import tools.jackson.databind.JsonNode;

/**
 * Generic receiver for inbound envelopes other than REQUEST (which is served by
 * {@link NodeRequestHandler}): acknowledgements, and also STREAM_REQUEST and
 * CANCEL.
 *
 * A Worker serves streaming here: on STREAM_REQUEST it deserializes the payload
 * (a {@code WorkerRequest}), runs its streaming inference, and sends
 * STREAM_CHUNK... then STREAM_COMPLETE or STREAM_ERROR through
 * {@link OutboundNodeConnection#send}, each with the STREAM_REQUEST's
 * {@code requestId}; on CANCEL it stops the inference with that requestId. The
 * SDK itself holds no streaming state.
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
