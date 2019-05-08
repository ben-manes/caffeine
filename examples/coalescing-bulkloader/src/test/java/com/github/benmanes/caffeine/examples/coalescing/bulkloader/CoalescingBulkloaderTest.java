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

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public final class CoalescingBulkloaderTest {

    @NonNull private AsyncLoadingCache<Integer, Integer> createCache(AtomicInteger loaderCalled) {
        return Caffeine.newBuilder().buildAsync(
                new CoalescingBulkloader<Integer, Integer>(10, 50) {
                    @Override protected void load(Map<Integer, CompletableFuture<Integer>> toLoad) {
                        ForkJoinPool.commonPool().execute(() -> {
                            loaderCalled.incrementAndGet();
                            try {
                                Thread.sleep(20);
                                toLoad.forEach((k, v) -> v.complete(k));
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        });
                    }
                });
    }

    @Test
    public void maxDelayIsNotMissedTooMuch() throws ExecutionException, InterruptedException {
        AtomicInteger loaderCalled = new AtomicInteger(0);
        final AsyncLoadingCache<Integer, Integer> cache = createCache(loaderCalled);

        // a cache get won't take too long
        final CompletableFuture<Integer> result = cache.get(1);
        Awaitility.await().pollThread(Thread::new).pollInterval(1, MILLISECONDS)
                .between(45, MILLISECONDS, 55, MILLISECONDS)
                .untilAtomic(loaderCalled, is(1));
        assertTrue("delay in load", !result.isDone());
        Thread.sleep(20);
        assertThat(result.getNow(0), is(1));
    }

    @Test
    public void whenEnoughKeysAreRequestedTheLoadWillHappenImmediately() throws InterruptedException {
        AtomicInteger loaderCalled = new AtomicInteger(0);
        final AsyncLoadingCache<Integer, Integer> cache = createCache(loaderCalled);

        CompletableFuture<Integer>[] results = new CompletableFuture[10];
        for (int i = 0; i < 9; i++)
            results[i] = cache.get(i);
        Thread.sleep(5);
        // requesting 9 keys does not trigger a load
        assertThat(loaderCalled.get(), is(0));

        for (int i = 0; i < 9; i++) {
            final CompletableFuture<Integer> result = cache.get(i);
            assertThat(result, sameInstance(results[i]));
            assertTrue("no load therefore unknown result", !result.isDone());
        }
        Thread.sleep(5);
        // requesting the same 9 keys still doesn't trigger a load
        assertThat(loaderCalled.get(), is(0));

        // requesting the 10 key will trigger immediately
        results[9] = cache.get(9);
        Awaitility.await().pollInterval(1, MILLISECONDS)
                .atMost(15, MILLISECONDS)
                .untilAtomic(loaderCalled, is(Integer.valueOf(1)));

        // values are not immediately available because of the sleep in the loader
        for (int i = 0; i < 10; i++) {
            assertThat(results[i].getNow(-1), is(-1));
        }
        Thread.sleep(20);
        // slept enough
        for (int i = 0; i < 10; i++) {
            assertThat(results[i].getNow(-1), is(i));
        }

    }

    @Rule
    // Because the jvm may have to warm up or whatever other influences, timing may be off, causing these tests to fail.
    // So retry a couple of times.
    public TestRule retry = (final Statement base, final Description description) -> new Statement() {

        @Override
        public void evaluate() throws Throwable {
            trie(3);
        }

        void trie(int tries) throws Throwable {
                try {
                    base.evaluate();
                } catch (Throwable throwable) {
                    System.err.println(description.getDisplayName() + " failed, " + (tries - 1) + " attempts left.");
                    if(tries>1)
                        trie(tries-1);
                    else {
                        throw throwable;
                    }
                }
        }
    };

}
