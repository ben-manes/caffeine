package com.github.benmanes.caffeine.cache;

import com.google.common.cache.CacheBuilder;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import static com.github.benmanes.caffeine.cache.ComputeBenchmark.cacheLoader;
import static com.github.benmanes.caffeine.cache.ComputeBenchmark.mappingFunction;

/**
 * @author ashishojha
 */
abstract class BenchmarkFunctionFactory {
    abstract Function<Integer, Boolean> create();
}

class ConcurrentHashMapFunctionFactory extends BenchmarkFunctionFactory {
    @Override
    Function<Integer, Boolean> create() {
        ConcurrentMap<Integer, Boolean> map = new ConcurrentHashMap<>();
        return key -> map.computeIfAbsent(key, mappingFunction);
    }
}

class CaffeineFunctionFactory extends BenchmarkFunctionFactory {
    @Override
    Function<Integer, Boolean> create() {
        Cache<Integer, Boolean> cache = Caffeine.newBuilder().build();
        return key -> cache.get(key, mappingFunction);
    }
}

class GuavaFunctionFactory extends BenchmarkFunctionFactory {
    @Override
    Function<Integer, Boolean> create() {
        com.google.common.cache.LoadingCache<Integer, Boolean> cache =
                CacheBuilder.newBuilder().concurrencyLevel(64).build(cacheLoader);
        return cache::getUnchecked;
    }
}
