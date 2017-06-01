package com.github.benmanes.caffeine.examples.writebehind;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jayway.awaitility.Awaitility;

import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BinaryOperator;

public class WriteBehindCacheWriterTest {

    @Test
    public void givenCacheUpdate_writeBehindIsCalled() {
        AtomicBoolean writerCalled = new AtomicBoolean(false);

        // Given this cache...
        Cache<Long, ZonedDateTime> cache = Caffeine.newBuilder()
                .writer(new WriteBehindCacheWriter<>(
                        Duration.ofSeconds(1),
                        BinaryOperator.maxBy(ZonedDateTime::compareTo),
                        (Map<Long, ZonedDateTime> entries) -> {
                            writerCalled.set(true);
                        }))
                .build();

        // When this cache update happens ...
        cache.put(1L, ZonedDateTime.now());

        // Then the write behind action is called
        Awaitility.await().untilAtomic(writerCalled, Matchers.is(true));
    }

    @Test
    public void givenCacheUpdateOnMultipleKeys_writeBehindIsCalled() {
        AtomicBoolean writerCalled = new AtomicBoolean(false);
        AtomicInteger numberOfEntries = new AtomicInteger(0);

        // Given this cache...
        Cache<Long, ZonedDateTime> cache = Caffeine.newBuilder()
                .writer(new WriteBehindCacheWriter<>(
                        Duration.ofSeconds(1),
                        BinaryOperator.maxBy(ZonedDateTime::compareTo),
                        (Map<Long, ZonedDateTime> entries) -> {
                            numberOfEntries.set(entries.size());
                            writerCalled.set(true);
                        }))
                .build();

        // When these cache updates happen ...
        cache.put(1L, ZonedDateTime.now());
        cache.put(2L, ZonedDateTime.now());
        cache.put(3L, ZonedDateTime.now());

        // Then the write behind action gets 3 entries to write
        Awaitility.await().untilAtomic(writerCalled, Matchers.is(true));
        Assert.assertEquals(3, numberOfEntries.intValue());
    }

    @Test
    public void givenMultipleCacheUpdatesOnSameKey_writeBehindIsCalledWithMostRecentTime() {
        AtomicBoolean writerCalled = new AtomicBoolean(false);
        AtomicInteger numberOfEntries = new AtomicInteger(0);
        AtomicReference<ZonedDateTime> timeInWriteBehind = new AtomicReference<>();

        // Given this cache...
        Cache<Long, ZonedDateTime> cache = Caffeine.newBuilder()
                .writer(new WriteBehindCacheWriter<>(
                        Duration.ofSeconds(1),
                        BinaryOperator.maxBy(ZonedDateTime::compareTo),
                        (Map<Long, ZonedDateTime> entries) -> {
                            // We might get here before the cache has been written to,
                            // so just wait for the next time we are called
                            if (entries.isEmpty())
                            {
                                return;
                            }

                            numberOfEntries.set(entries.size());
                            ZonedDateTime zonedDateTime = entries.values().iterator().next();

                            timeInWriteBehind.updateAndGet(d -> zonedDateTime);

                            writerCalled.set(true);
                        }))
                .build();

        // When these cache updates happen ...
        cache.put(1L, ZonedDateTime.of(2016, 6, 26, 8, 0, 0, 0, ZoneId.systemDefault()));
        cache.put(1L, ZonedDateTime.of(2016, 6, 26, 8, 0, 0, 100, ZoneId.systemDefault()));
        cache.put(1L, ZonedDateTime.of(2016, 6, 26, 8, 0, 0, 300, ZoneId.systemDefault()));
        ZonedDateTime mostRecentTime = ZonedDateTime.of(2016, 6, 26, 8, 0, 0, 500, ZoneId.systemDefault());
        cache.put(1L, mostRecentTime);

        // Then the write behind action gets 1 entry to write with the most recent time
        Awaitility.await().untilAtomic(writerCalled, Matchers.is(true));
        Assert.assertEquals(1, numberOfEntries.intValue());
        Assert.assertEquals(mostRecentTime, timeInWriteBehind.get());
    }
}
