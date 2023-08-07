/*
 * Copyright 2019 Guus C. Bloemsma. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.examples.coalescing.bulkloader;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import java.time.Duration;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * An {@link AsyncCacheLoader} that accumulates keys until a specified maximum size or time limit is
 * reached, at which point it performs a bulk load. This strategy assumes that the efficiency gained
 * through batching justifies the wait.
 *
 * @author guus@bloemsma.net (Guus C. Bloemsma)
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CoalescingBulkLoader<K, V> implements AsyncCacheLoader<K, V> {
  private final Sinks.Many<Entry<K, CompletableFuture<V>>> sink;
  private final Function<Set<K>, Map<K, V>> mappingFunction;

  private CoalescingBulkLoader(Builder<K, V> builder) {
    this.mappingFunction = builder.mappingFunction;
    sink = Sinks.many().unicast().onBackpressureBuffer();
    sink.asFlux()
        .bufferTimeout(builder.maxSize, builder.maxTime)
        .map(requests -> requests.stream().collect(
            toMap(Entry::getKey, Entry::getValue)))
        .parallel(builder.parallelism)
        .runOn(Schedulers.boundedElastic())
        .subscribe(this::handle);
  }

  @Override
  public synchronized CompletableFuture<V> asyncLoad(K key, Executor executor) {
    var entry = Map.entry(key, new CompletableFuture<V>());
    sink.tryEmitNext(entry).orThrow();
    return entry.getValue();
  }

  private void handle(Map<K, CompletableFuture<V>> requests) {
    try {
      var results = mappingFunction.apply(requests.keySet());
      requests.forEach((key, result) -> result.complete(results.get(key)));
    } catch (Throwable t) {
      requests.forEach((key, result) -> result.completeExceptionally(t));
    }
  }

  public static final class Builder<K, V> {
    private Function<Set<K>, Map<K, V>> mappingFunction;
    private Duration maxTime;
    private int parallelism;
    private int maxSize;

    /** The maximum collected size before performing a bulk request. */
    @CanIgnoreReturnValue
    public Builder<K, V> maxSize(int maxSize) {
      this.maxSize = requireNonNull(maxSize);
      return this;
    }

    /** The maximum duration to wait before performing a bulk request. */
    @CanIgnoreReturnValue
    public Builder<K, V> maxTime(Duration maxTime) {
      this.maxTime = requireNonNull(maxTime);
      return this;
    }

    /** The number of parallel bulk loads that can be performed. */
    @CanIgnoreReturnValue
    public Builder<K, V> parallelism(int parallelism) {
      this.parallelism = requireNonNull(parallelism);
      return this;
    }

    /** The function to compute the values. */
    @CanIgnoreReturnValue
    public Builder<K, V> mappingFunction(Function<Set<K>, Map<K, V>> mappingFunction) {
      this.mappingFunction = requireNonNull(mappingFunction);
      return this;
    }

    /** Returns a loader that batches the individual lookups to the system of record. */
    public CoalescingBulkLoader<K, V> build() {
      requireNonNull(mappingFunction);
      return new CoalescingBulkLoader<>(this);
    }
  }
}
