package com.inframesh.node.monitor;

import java.util.concurrent.atomic.AtomicInteger;

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
