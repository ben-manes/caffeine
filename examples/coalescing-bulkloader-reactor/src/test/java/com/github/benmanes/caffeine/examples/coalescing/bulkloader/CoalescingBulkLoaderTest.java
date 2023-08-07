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

import static com.google.common.truth.Truth.assertThat;
import static java.util.stream.Collectors.toMap;

import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.Range;

/**
 * @author guus@bloemsma.net (Guus C. Bloemsma)
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CoalescingBulkLoaderTest {

  @Test
  public void maxTime() {
    var maxTime = Duration.ofMillis(50);
    var end = new AtomicReference<Long>();
    var loader = new CoalescingBulkLoader.Builder<Integer, Integer>()
        .mappingFunction(keys -> {
          end.set(System.nanoTime());
          return keys.stream().collect(toMap(key -> key, key -> -key));
        })
        .maxTime(maxTime)
        .parallelism(3)
        .maxSize(5)
        .build();
    var cache = Caffeine.newBuilder().buildAsync(loader);

    var start = System.nanoTime();
    assertThat(cache.get(1).join()).isEqualTo(-1);

    long delay = TimeUnit.NANOSECONDS.toMillis(end.get() - start);
    assertThat(delay).isIn(Range.closed(maxTime.toMillis(), (long) (1.5 * maxTime.toMillis())));
  }

  @Test
  public void maxSize() {
    int maxSize = 5;
    int requests = 5 * maxSize;
    var maxTime = Duration.ofSeconds(1);
    var end = new AtomicReference<Long>();
    var batchSizes = new ConcurrentLinkedQueue<Integer>();
    var loader = new CoalescingBulkLoader.Builder<Integer, Integer>()
        .mappingFunction(keys -> {
          end.set(System.nanoTime());
          batchSizes.add(keys.size());
          return keys.stream().collect(toMap(key -> key, key -> -key));
        })
        .maxTime(maxTime)
        .maxSize(maxSize)
        .parallelism(3)
        .build();
    var cache = Caffeine.newBuilder().buildAsync(loader);

    var start = System.nanoTime();
    var results = new HashMap<Integer, CompletableFuture<Integer>>();
    for (int i = 0; i < requests; i++) {
      results.put(i, cache.get(i));
    }

    results.forEach((key, future) -> assertThat(future.join()).isEqualTo(-key));
    batchSizes.forEach(batchSize-> assertThat(batchSize).isAtMost(maxSize));
    assertThat(batchSizes).hasSize(requests / maxSize);

    long delay = TimeUnit.NANOSECONDS.toMillis(end.get() - start);
    assertThat(delay).isLessThan(maxTime.toMillis());
  }
}
