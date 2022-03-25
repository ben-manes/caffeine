package com.github.benmanes.caffeine.cache.simulator.parser;

import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;

import java.util.stream.LongStream;
import java.util.stream.Stream;

public interface KeyOnlyTraceReader extends TraceReader.KeyTraceReader {

    @Override default Stream<AccessEvent> events() {
        return keys().mapToObj(AccessEvent::forKey);
    }

    public LongStream keys();
}
