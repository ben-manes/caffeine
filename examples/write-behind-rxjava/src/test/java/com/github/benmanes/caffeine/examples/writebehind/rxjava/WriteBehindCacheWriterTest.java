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

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BinaryOperator;

import org.awaitility.Awaitility;
import org.junit.Assert;
import org.junit.Test;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * An example of a write-behind cache.
 *
 * @author wim.deblauwe@gmail.com (Wim Deblauwe)
 */
public final class WriteBehindCacheWriterTest {

  @Test
  public void givenCacheUpdate_writeBehindIsCalled() {
    AtomicBoolean writerCalled = new AtomicBoolean(false);

    // Given this cache...
    Cache<Long, ZonedDateTime> cache = Caffeine.newBuilder()
        .writer(new WriteBehindCacheWriter.Builder<Long, ZonedDateTime>()
            .bufferTime(1, TimeUnit.SECONDS)
            .coalesce(BinaryOperator.maxBy(ZonedDateTime::compareTo))
            .writeAction(entries -> writerCalled.set(true))
            .build())
        .build();

    // When this cache update happens ...
    cache.put(1L, ZonedDateTime.now());

    // Then the write behind action is called
    Awaitility.await().untilTrue(writerCalled);
  }

  @Test
  public void givenCacheUpdateOnMultipleKeys_writeBehindIsCalled() {
    AtomicBoolean writerCalled = new AtomicBoolean(false);
    AtomicInteger numberOfEntries = new AtomicInteger(0);

    // Given this cache...
    Cache<Long, ZonedDateTime> cache = Caffeine.newBuilder()
        .writer(new WriteBehindCacheWriter.Builder<Long, ZonedDateTime>()
            .bufferTime(1, TimeUnit.SECONDS)
            .coalesce(BinaryOperator.maxBy(ZonedDateTime::compareTo))
            .writeAction(entries -> {
              numberOfEntries.set(entries.size());
              writerCalled.set(true);
            }).build())
        .build();

    // When these cache updates happen ...
    cache.put(1L, ZonedDateTime.now());
    cache.put(2L, ZonedDateTime.now());
    cache.put(3L, ZonedDateTime.now());

    // Then the write behind action gets 3 entries to write
    Awaitility.await().untilTrue(writerCalled);
    Assert.assertEquals(3, numberOfEntries.intValue());
  }

  @Test
  public void givenMultipleCacheUpdatesOnSameKey_writeBehindIsCalledWithMostRecentTime() {
    AtomicBoolean writerCalled = new AtomicBoolean(false);
    AtomicInteger numberOfEntries = new AtomicInteger(0);
    AtomicReference<ZonedDateTime> timeInWriteBehind = new AtomicReference<>();

    // Given this cache...
    Cache<Long, ZonedDateTime> cache = Caffeine.newBuilder()
        .writer(new WriteBehindCacheWriter.Builder<Long, ZonedDateTime>()
            .bufferTime(1, TimeUnit.SECONDS)
            .coalesce(BinaryOperator.maxBy(ZonedDateTime::compareTo))
            .writeAction(entries -> {
              // We might get here before the cache has been written to,
              // so just wait for the next time we are called
              if (entries.isEmpty()) {
                return;
              }

              numberOfEntries.set(entries.size());
              ZonedDateTime zonedDateTime = entries.values().iterator().next();
              timeInWriteBehind.set(zonedDateTime);
              writerCalled.set(true);
            }).build())
        .build();

    // When these cache updates happen ...
    cache.put(1L, ZonedDateTime.of(2016, 6, 26, 8, 0, 0, 0, ZoneId.systemDefault()));
    cache.put(1L, ZonedDateTime.of(2016, 6, 26, 8, 0, 0, 100, ZoneId.systemDefault()));
    cache.put(1L, ZonedDateTime.of(2016, 6, 26, 8, 0, 0, 300, ZoneId.systemDefault()));
    ZonedDateTime mostRecentTime = ZonedDateTime.of(
        2016, 6, 26, 8, 0, 0, 500, ZoneId.systemDefault());
    cache.put(1L, mostRecentTime);

    // Then the write behind action gets 1 entry to write with the most recent time
    Awaitility.await().untilTrue(writerCalled);
    Assert.assertEquals(1, numberOfEntries.intValue());
    Assert.assertEquals(mostRecentTime, timeInWriteBehind.get());
  }
}
