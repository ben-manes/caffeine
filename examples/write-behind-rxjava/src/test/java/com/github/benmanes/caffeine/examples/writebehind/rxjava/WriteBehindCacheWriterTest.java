/*
 * Copyright 2016 Wim Deblauwe. All Rights Reserved.
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
package com.github.benmanes.caffeine.examples.writebehind.rxjava;

import static java.time.temporal.ChronoUnit.DAYS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BinaryOperator;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * An example of a write-behind cache.
 *
 * @author wim.deblauwe@gmail.com (Wim Deblauwe)
 */
final class WriteBehindCacheWriterTest {

  @Test
  void singleKey() {
    var writerCalled = new AtomicBoolean();

    // Given this cache...
    var writer = new WriteBehindCacheWriter.Builder<Integer, ZonedDateTime>()
        .coalesce(BinaryOperator.maxBy(ZonedDateTime::compareTo))
        .writeAction(_ -> writerCalled.set(true))
        .bufferTime(Duration.ofSeconds(1))
        .build();
    Cache<Integer, ZonedDateTime> cache = Caffeine.newBuilder().build();

    // When this cache update happens...
    cache.asMap().computeIfAbsent(1, key -> {
      var value = ZonedDateTime.now();
      writer.accept(key, value);
      return value;
    });

    // Then the write behind action is called
    await().untilTrue(writerCalled);
  }

  @Test
  void multipleKeys() {
    var numberOfEntries = new AtomicInteger();

    // Given this cache...
    var writer = new WriteBehindCacheWriter.Builder<Integer, ZonedDateTime>()
        .writeAction(entries -> numberOfEntries.addAndGet(entries.size()))
        .coalesce(BinaryOperator.maxBy(ZonedDateTime::compareTo))
        .bufferTime(Duration.ofSeconds(1))
        .build();
    Cache<Integer, ZonedDateTime> cache = Caffeine.newBuilder().build();

    // When these cache updates happen...
    for (int i = 1; i <= 3; i++) {
      cache.asMap().computeIfAbsent(i, key -> {
        var value = ZonedDateTime.now();
        writer.accept(key, value);
        return value;
      });
    }

    // Then the write behind action gets 3 entries to write
    await().untilAtomic(numberOfEntries, is(3));
  }

  @Test
  void singleKey_mostRecent() {
    var timeInWriteBehind = new AtomicReference<ZonedDateTime>();
    var numberOfEntries = new AtomicInteger();

    // Given this cache...
    var writer = new WriteBehindCacheWriter.Builder<Long, ZonedDateTime>()
        .coalesce(BinaryOperator.maxBy(ZonedDateTime::compareTo))
        .bufferTime(Duration.ofSeconds(1))
        .writeAction(entries -> {
          var zonedDateTime = entries.values().iterator().next();
          timeInWriteBehind.set(zonedDateTime);
          numberOfEntries.set(entries.size());
        }).build();
    Cache<Long, ZonedDateTime> cache = Caffeine.newBuilder().build();

    // When these cache updates happen...
    var latest = ZonedDateTime.now().truncatedTo(DAYS);
    for (int i = 0; i < 4; i++) {
      latest = latest.plusNanos(200);

      var value = latest;
      cache.asMap().compute(1L, (key, _) -> {
        writer.accept(key, value);
        return value;
      });
    }

    // Then the write behind action gets 1 entry to write with the most recent time
    await().untilAtomic(numberOfEntries, is(1));
    await().untilAtomic(timeInWriteBehind, is(latest));
  }

  @Test
  void writeActionThrows_keepsWriting() {
    var firstBatch = new AtomicBoolean(true);
    var written = new ConcurrentLinkedQueue<Integer>();
    var writer = new WriteBehindCacheWriter.Builder<Integer, Integer>()
        .coalesce((_, second) -> second)
        .bufferTime(Duration.ofMillis(50))
        .writeAction(batch -> {
          if (firstBatch.getAndSet(false)) {
            throw new IllegalStateException("write failed");
          }
          written.addAll(batch.keySet());
        }).build();

    // A throw from the write action must not tear down the pipeline: later writes still flow
    writer.accept(1, 1);
    await().untilFalse(firstBatch);
    writer.accept(2, 2);
    await().until(() -> written.contains(2));
  }

  @Test
  void writeActionThrowsError_keepsWriting() {
    var firstBatch = new AtomicBoolean(true);
    var written = new ConcurrentLinkedQueue<Integer>();
    var writer = new WriteBehindCacheWriter.Builder<Integer, Integer>()
        .coalesce((_, second) -> second)
        .bufferTime(Duration.ofMillis(50))
        .writeAction(batch -> {
          if (firstBatch.getAndSet(false)) {
            throw new AssertionError("write failed");
          }
          written.addAll(batch.keySet());
        }).build();

    // A non-RuntimeException (Error) from the write action must also not terminate the
    // subscription; otherwise every subsequent write is silently dropped
    writer.accept(1, 1);
    await().untilFalse(firstBatch);
    writer.accept(2, 2);
    await().until(() -> written.contains(2));
  }

  @Test
  void coalescerThrows_keepsWriting() {
    var firstBatch = new AtomicBoolean(true);
    var written = new ConcurrentLinkedQueue<Integer>();
    var writer = new WriteBehindCacheWriter.Builder<Integer, Integer>()
        .coalesce((first, second) -> {
          if (firstBatch.getAndSet(false)) {
            throw new IllegalStateException("coalesce failed");
          }
          return second;
        })
        .bufferTime(Duration.ofMillis(50))
        .writeAction(batch -> written.addAll(batch.keySet())).build();

    // A throw from the coalescer must not tear down the pipeline: later writes still flow
    writer.accept(1, 1);
    writer.accept(1, 2);
    await().untilFalse(firstBatch);
    writer.accept(2, 2);
    await().until(() -> written.contains(2));
  }

  @Test
  void emptyBatch_notDelivered() {
    var batchSizes = new ConcurrentLinkedQueue<Integer>();
    var writer = new WriteBehindCacheWriter.Builder<Integer, Integer>()
        .coalesce((_, second) -> second)
        .bufferTime(Duration.ofMillis(20))
        .writeAction(batch -> batchSizes.add(batch.size())).build();

    // The timed buffer must not deliver empty batches to the write action during idle intervals
    writer.accept(1, 1);
    await().until(() -> batchSizes.contains(1));
    await().during(Duration.ofMillis(150)).until(() -> !batchSizes.contains(0));
  }

  @Test
  void invalidArguments() {
    var builder = new WriteBehindCacheWriter.Builder<Integer, Integer>();
    assertThrows(IllegalArgumentException.class, () -> builder.bufferTime(Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> builder.bufferTime(Duration.ofMillis(-1)));
  }
}
