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
import static java.util.stream.Collectors.toMap;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * An implementation of {@link AsyncCacheLoader} that delays fetching a bit until "enough" keys are collected
 * to do a bulk call. The assumption is that doing a bulk call is so much more efficient that it is worth
 * the wait.
 *
 * @param <Key>   the type of the key in the cache
 * @param <Value> the type of the value in the cache
 * @author complain to: guus@bloemsma.net
 */
public class CoalescingBulkloader<Key, Value> implements AsyncCacheLoader<Key, Value> {
    private final Consumer<Collection<WaitingKey>> bulkLoader;
    private int maxLoadSize; // maximum number of keys to load in one call
    private long maxDelay; // maximum time between request of a value and loading it
    private volatile Queue<WaitingKey> waitingKeys = new ConcurrentLinkedQueue<>();
    private ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> schedule;
    // Queue.size() is expensive, so here we keep track of the queue size separately
    private AtomicInteger size = new AtomicInteger(0);

    private final class WaitingKey {
        Key key;
        CompletableFuture<Value> future;
        long waitingSince;
    }

    /**
     * Wraps a bulk loader that returns values in the same order as the keys.
     *
     * @param maxLoadSize Maximum number of keys per bulk load
     * @param maxDelay    Maximum time to wait before bulk load is executed
     * @param load        Loader that takes keys and returns a future with the values in the same order as the keys.
     *                    Extra values are ignored. Missing values lead to a {@link java.util.NoSuchElementException}
     *                    for the corresponding future.
     */
    public static <Key, Value> CoalescingBulkloader<Key, Value> byOrder(int maxLoadSize, long maxDelay,
            final Function<Stream<Key>, CompletableFuture<Stream<Value>>> load) {
        return new CoalescingBulkloader<>(maxLoadSize, maxDelay, toLoad -> {
            final Stream<Key> keys = toLoad.stream().map(wk -> wk.key);
            load.apply(keys).thenAccept(values -> {
                final Iterator<Value> iv = values.iterator();
                for (CoalescingBulkloader<Key, Value>.WaitingKey waitingKey : toLoad) {
                    if (iv.hasNext())
                        waitingKey.future.complete(iv.next());
                    else
                        waitingKey.future.completeExceptionally(new NoSuchElementException("No value for key " + waitingKey.key));
                }
            });
        });
    }

    /**
     * Wraps a bulk loader that returns values in a map accessed by key.
     *
     * @param maxLoadSize Maximum number of keys per bulk load
     * @param maxDelay    Maximum time to wait before bulk load is executed
     * @param load        Loader that takes keys and returns a future with a map with keys and values.
     *                    Extra values are ignored. Missing values lead to a {@link java.util.NoSuchElementException}
     *                    for the corresponding future.
     */
    public static <Key, Value> CoalescingBulkloader<Key, Value> byMap(int maxLoadSize, long maxDelay,
            final Function<Stream<Key>, CompletableFuture<Map<Key, Value>>> load) {
        return new CoalescingBulkloader<>(maxLoadSize, maxDelay, toLoad -> {
            final Stream<Key> keys = toLoad.stream().map(wk -> wk.key);
            load.apply(keys).thenAccept(values -> {
                for (CoalescingBulkloader<Key, Value>.WaitingKey waitingKey : toLoad) {
                    if (values.containsKey(waitingKey.key))
                        waitingKey.future.complete(values.get(waitingKey.key));
                    else
                        waitingKey.future.completeExceptionally(new NoSuchElementException("No value for key " + waitingKey.key));
                }
            });
        });
    }

    /**
     * Wraps a bulk loader that returns intermediate values from which keys and values can be extracted.
     *
     * @param <Intermediate> Some internal type from which keys and values can be extracted.
     * @param maxLoadSize    Maximum number of keys per bulk load
     * @param maxDelay       Maximum time to wait before bulk load is executed
     * @param keyExtractor   How to extract key from intermediate value
     * @param valueExtractor How to extract value from intermediate value
     * @param load           Loader that takes keys and returns a future with a map with keys and values.
     *                       Extra values are ignored. Missing values lead to a {@link java.util.NoSuchElementException}
     *                       for the corresponding future.
     */
    public static <Key, Value, Intermediate> CoalescingBulkloader<Key, Value> byExtraction(int maxLoadSize, long maxDelay,
            final Function<Intermediate, Key> keyExtractor,
            final Function<Intermediate, Value> valueExtractor,
            final Function<Stream<Key>, CompletableFuture<Stream<Intermediate>>> load) {
        return byMap(maxLoadSize, maxDelay,
                keys -> load.apply(keys).thenApply(
                        intermediates -> intermediates.collect(toMap(keyExtractor, valueExtractor))));
    }

    private CoalescingBulkloader(int maxLoadSize, long maxDelay, Consumer<Collection<WaitingKey>> bulkLoader) {
        this.bulkLoader = bulkLoader;
        assert maxLoadSize > 0;
        assert maxDelay > 0;
        this.maxLoadSize = maxLoadSize;
        this.maxDelay = maxDelay;
    }

    @Override public CompletableFuture<Value> asyncLoad(Key key, Executor executor) {
        final WaitingKey waitingKey = new WaitingKey();
        waitingKey.key = key;
        waitingKey.future = new CompletableFuture<>();
        waitingKey.waitingSince = System.currentTimeMillis();
        waitingKeys.add(waitingKey);

        if (size.incrementAndGet() >= maxLoadSize) {
            doLoad();
        } else if (schedule == null || schedule.isDone()) {
            startWaiting();
        }

        return waitingKey.future;
    }

    synchronized private void startWaiting() {
        schedule = timer.schedule(this::doLoad, maxDelay, MILLISECONDS);
    }

    synchronized private void doLoad() {
        schedule.cancel(false);
        do {
            List<WaitingKey> toLoad = new ArrayList<>(Math.min(size.get(), maxLoadSize));
            int counter = maxLoadSize;
            while (counter > 0) {
                final WaitingKey waitingKey = waitingKeys.poll();
                if (waitingKey == null)
                    break;
                else {
                    toLoad.add(waitingKey);
                    counter--;
                }
            }

            final int taken = maxLoadSize - counter;
            if (taken > 0) {
                bulkLoader.accept(toLoad);
                size.updateAndGet(oldSize -> oldSize - taken);
            }

        } while (size.get() >= maxLoadSize);
        final WaitingKey nextWaitingKey = waitingKeys.peek();
        if (nextWaitingKey != null) {
            schedule = timer.schedule(this::doLoad, nextWaitingKey.waitingSince + maxDelay - System.currentTimeMillis(), MILLISECONDS);
        }
    }

}
