package com.inframesh.node.monitor;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Number of requests this Worker is running inference for right now, reported
 * as {@code NodeHealthResponse.runtime.activeRequests}.
 *
 * Nothing else: not Console's in-flight count, a queue size, pending WebSocket
 * messages, HTTP connections, or a Router's routing requests.
 *
 * For requests served over the OUTBOUND connection the SDK maintains it
 * ({@code WebSocketOutboundNodeConnection}): a Worker's {@code NodeRequestHandler}
 * / {@code NodeStreamRequestHandler} must not increment it again.
 */
public class ActiveRequestCounter {

    private final AtomicInteger count = new AtomicInteger();

    public void increment() {
        count.incrementAndGet();
    }

    public void decrement() {
        count.decrementAndGet();
    }

    public int get() {
        return count.get();
    }
}
