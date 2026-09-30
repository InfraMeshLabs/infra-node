package com.inframesh.node.connection;

import com.inframesh.node.dto.connection.NodeEnvelope;
import tools.jackson.databind.JsonNode;

/**
 * Generic receiver for inbound envelopes other than REQUEST (which is served by
 * {@link NodeRequestHandler}), e.g. acknowledgements or control messages added
 * to the protocol later.
 *
 * Every registered handler receives every such envelope, with the payload left
 * as raw JSON - the connection SDK does not interpret message semantics.
 * Handlers are invoked off the WebSocket listener thread and must not assume
 * ordering across messages.
 */
@FunctionalInterface
public interface NodeMessageHandler {

    void handle(NodeEnvelope<JsonNode> envelope);
}
