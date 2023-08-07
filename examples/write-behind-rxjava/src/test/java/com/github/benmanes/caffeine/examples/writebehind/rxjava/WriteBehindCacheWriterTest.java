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

import java.time.Duration;
import java.time.ZonedDateTime;
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
public final class WriteBehindCacheWriterTest {

  @Test
  public void singleKey() {
    var writerCalled = new AtomicBoolean();

    // Given this cache...
    var writer = new WriteBehindCacheWriter.Builder<Integer, ZonedDateTime>()
        .coalesce(BinaryOperator.maxBy(ZonedDateTime::compareTo))
        .writeAction(entries -> writerCalled.set(true))
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
  public void multipleKeys() {
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
  public void singleKey_mostRecent() {
    var timeInWriteBehind = new AtomicReference<ZonedDateTime>();
    var numberOfEntries = new AtomicInteger();

    // Given this cache...
    var writer = new WriteBehindCacheWriter.Builder<Long, ZonedDateTime>()
        .coalesce(BinaryOperator.maxBy(ZonedDateTime::compareTo))
        .bufferTime(Duration.ofSeconds(1))
        .writeAction(entries -> {
          // We might get here before the cache has been written to,
          // so just wait for the next time we are called
          if (entries.isEmpty()) {
            return;
          }

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
      cache.asMap().compute(1L, (key, oldValue) -> {
        writer.accept(key, value);
        return value;
      });
    }

    // Then the write behind action gets 1 entry to write with the most recent time
    await().untilAtomic(numberOfEntries, is(1));
    await().untilAtomic(timeInWriteBehind, is(latest));
  }
}
