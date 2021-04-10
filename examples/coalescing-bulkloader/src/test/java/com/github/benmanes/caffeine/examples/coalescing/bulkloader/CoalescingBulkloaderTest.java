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

import static com.github.benmanes.caffeine.examples.coalescing.bulkloader.CoalescingBulkloader.byExtraction;
import static com.github.benmanes.caffeine.examples.coalescing.bulkloader.CoalescingBulkloader.byMap;
import static com.github.benmanes.caffeine.examples.coalescing.bulkloader.CoalescingBulkloader.byOrder;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.awaitility.Awaitility;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.junit.runners.model.Statement;

@RunWith(Parameterized.class)
public final class CoalescingBulkloaderTest {

    private final Function<Function<Stream<Integer>, Stream<Integer>>, CoalescingBulkloader<Integer, Integer>> cbl;

    public CoalescingBulkloaderTest(Function<Function<Stream<Integer>, Stream<Integer>>, CoalescingBulkloader<Integer, Integer>> cbl) {
        this.cbl = cbl;
    }

    private final static int maxLoadSize = 10;
    private final static int maxDelay = 50;
    private final static int delta = 5;
    private final static int actualLoadTime = 20;

    @Parameters
    public static List<Function<Function<Stream<Integer>, Stream<Integer>>, CoalescingBulkloader<Integer, Integer>>> loaderTypes() {
        return Arrays.asList(
                fun -> byOrder(maxLoadSize, maxDelay,
                        ints -> CompletableFuture.supplyAsync(() -> fun.apply(ints))),
                fun -> byMap(maxLoadSize, maxDelay,
                        ints -> CompletableFuture.supplyAsync(() -> fun.apply(ints).collect(toMap(identity(), identity())))),
                fun -> byExtraction(maxLoadSize, maxDelay, identity(), identity(),
                        ints -> CompletableFuture.supplyAsync(() -> fun.apply(ints)))
        );
    }

    private AsyncLoadingCache<Integer, Integer> createCache(AtomicInteger loaderCalled) {
        return Caffeine.newBuilder().buildAsync(cbl.apply(ints -> {
            loaderCalled.incrementAndGet();
            try {
                Thread.sleep(actualLoadTime);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return ints;
        }));
    }

    @Test
    public void maxDelayIsNotMissedTooMuch() throws InterruptedException {
        AtomicInteger loaderCalled = new AtomicInteger(0);
        final AsyncLoadingCache<Integer, Integer> cache = createCache(loaderCalled);

        // a cache get won't take too long
        final CompletableFuture<Integer> result = cache.get(1);
        Awaitility.await().pollThread(Thread::new).pollInterval(1, MILLISECONDS)
                .between(maxDelay - delta, MILLISECONDS, maxDelay + delta, MILLISECONDS)
                .untilAtomic(loaderCalled, is(1));
        assertFalse("delay in load", result.isDone());
        Thread.sleep(actualLoadTime);
        assertThat(result.getNow(0), is(1));
    }

    @Test
    public void whenEnoughKeysAreRequestedTheLoadWillHappenImmediately() throws InterruptedException {
        AtomicInteger loaderCalled = new AtomicInteger(0);
        final AsyncLoadingCache<Integer, Integer> cache = createCache(loaderCalled);

        CompletableFuture<Integer>[] results = new CompletableFuture[maxLoadSize];
        for (int i = 0; i < maxLoadSize - 1; i++)
            results[i] = cache.get(i);
        Thread.sleep(delta);
        // requesting 9 keys does not trigger a load
        assertThat(loaderCalled.get(), is(0));

        for (int i = 0; i < maxLoadSize - 1; i++) {
            final CompletableFuture<Integer> result = cache.get(i);
            assertThat(result, sameInstance(results[i]));
            assertFalse("no load therefore unknown result", result.isDone());
        }
        Thread.sleep(delta);
        // requesting the same 9 keys still doesn't trigger a load
        assertThat(loaderCalled.get(), is(0));

        // requesting one more key will trigger immediately
        results[maxLoadSize - 1] = cache.get(maxLoadSize - 1);
        Awaitility.await().pollInterval(1, MILLISECONDS)
                .atMost(delta, MILLISECONDS)
                .untilAtomic(loaderCalled, is(Integer.valueOf(1)));

        // values are not immediately available because of the sleep in the loader
        for (int i = 0; i < maxLoadSize; i++) {
            assertThat(results[i].getNow(-1), is(-1));
        }
        Thread.sleep(actualLoadTime + delta);
        // slept enough
        for (int i = 0; i < maxLoadSize; i++) {
            assertThat(results[i].getNow(-1), is(i));
        }

    }

    @Rule
    // Because the jvm may have to warm up or whatever other influences, timing may be off, causing these tests to fail.
    // So retry a couple of times.
    public TestRule retry = (final Statement base, final Description description) -> new Statement() {

        @Override
        public void evaluate() throws Throwable {
            try_(3);
        }

        void try_(int tries) throws Throwable {
            try {
                base.evaluate();
            } catch (Throwable throwable) {
                System.err.println(description.getDisplayName() + " failed, " + (tries - 1) + " attempts left.");
                if (tries > 1)
                    try_(tries - 1);
                else {
                    throw throwable;
                }
            }
        }
    };

}
