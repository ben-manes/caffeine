package com.github.benmanes.caffeine.cache;

import static java.util.concurrent.TimeUnit.SECONDS;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A reproducer showing that {@link AsyncCache} is not always expiring correctly
 * with {@link Caffeine#expireAfterWrite(Duration)} and hence may return stale values or load to often even if
 * not necessary.
 */
@Test(singleThreaded = true)
public final class AsyncCacheExpiryReproducerTest {

    int count = 100000;

    @Test
    public void testExpiryWrite_everythingAlwaysExpire_closeToEachOther_BUG()
            throws InterruptedException, ExecutionException, TimeoutException {

        Duration expireAfterWrite = Duration.ofMillis(100);
        long tickerIncrementGetMillis = 101;
        long tickerIncrementCycleMillis = 101;
        // the value should always expire before another access but it's not always like this!
        long expectedCounterValue = count;

        verify(expireAfterWrite, tickerIncrementGetMillis, tickerIncrementCycleMillis, expectedCounterValue);
    }

    @Test
    public void testExpiryWrite_everythingAlwaysExpire_closeToEachOther_DAYS_BUG()
            throws InterruptedException, ExecutionException, TimeoutException {

        Duration expireAfterWrite = Duration.ofDays(100);
        long tickerIncrementGetMillis = TimeUnit.DAYS.toMillis(101);
        long tickerIncrementCycleMillis = TimeUnit.DAYS.toMillis(101);
        // the value should always expire before another access but it's not always like this!
        long expectedCounterValue = count;

        verify(expireAfterWrite, tickerIncrementGetMillis, tickerIncrementCycleMillis, expectedCounterValue);
    }

    @Test
    public void testExpiryWrite_everythingAlwaysExpire_farAwayFromEachOther_BUG()
            throws InterruptedException, ExecutionException, TimeoutException {

        Duration expireAfterWrite = Duration.ofMillis(2);
        long tickerIncrementGetMillis = 100;
        long tickerIncrementCycleMillis = 100;
        // the value should always expire before another access but it's not always like this!
        long expectedCounterValue = count;

        verify(expireAfterWrite, tickerIncrementGetMillis, tickerIncrementCycleMillis, expectedCounterValue);
    }

    @Test
    public void testExpiryWrite_everythingAlwaysExpire_farAwayFromEachOther_DAYS_BUG()
            throws InterruptedException, ExecutionException, TimeoutException {

        Duration expireAfterWrite = Duration.ofDays(2);
        long tickerIncrementGetMillis = TimeUnit.DAYS.toMillis(100);
        long tickerIncrementCycleMillis = TimeUnit.DAYS.toMillis(100);
        // the value should always expire before another access but it's not always like this!
        long expectedCounterValue = count;

        verify(expireAfterWrite, tickerIncrementGetMillis, tickerIncrementCycleMillis, expectedCounterValue);
    }

    @Test
    public void testExpiryWrite_firstValueNeverExpire_expireAfter1Minute_BUG()
            throws InterruptedException, ExecutionException, TimeoutException {

        // 1 minute of expiry should cause the loader to be called just once
        // but often it's not !!!!
        Duration expireAfterWrite = Duration.ofMinutes(1);
        long tickerIncrementGetMillis = 10;
        long tickerIncrementCycleMillis = 10;
        // the first value expires after 1 minute, so the result should be 1 !! but it's not
        long expectedCounterValue = 1;

        verify(expireAfterWrite, tickerIncrementGetMillis, tickerIncrementCycleMillis, expectedCounterValue);
    }

    @Test
    public void testExpiryWrite_firstValueNeverExpire_expireAfter1Hour_hopefullyOK()
            throws InterruptedException, ExecutionException, TimeoutException {

        Duration expireAfterWrite = Duration.ofHours(1);
        long tickerIncrementGetMillis = 10;
        long tickerIncrementCycleMillis = 10;
        // the first value never expires hence 1
        long expectedCounterValue = 1;

        verify(expireAfterWrite, tickerIncrementGetMillis, tickerIncrementCycleMillis, expectedCounterValue);
    }

    private void verify(Duration expireAfterWrite, long tickerIncrementGetMillis,
            long tickerIncrementCycleMillis, long expectedCounterValue)
            throws InterruptedException, ExecutionException,
            TimeoutException {

        AtomicLong counter = new AtomicLong(0);
        AtomicLong tickerMillis = new AtomicLong(0);
        AsyncCache<Integer, String> target = Caffeine.newBuilder()
                .expireAfterWrite(expireAfterWrite)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(tickerMillis.getAndAdd(tickerIncrementGetMillis)))
                .buildAsync();

        for (int i = 0; i < count; i++) {
            CompletableFuture<String> stringCompletableFuture = target.get(2,
                    (integer, executor) -> CompletableFuture
                            .supplyAsync(() -> "hello-from-loader-" + counter.getAndIncrement()));

            tickerMillis.getAndAdd(tickerIncrementCycleMillis);

            System.out.printf("Iteration: %d, value: %s%n", i, stringCompletableFuture.get(10, SECONDS));
        }

        Assert.assertEquals(counter.get(), expectedCounterValue);
    }
}
