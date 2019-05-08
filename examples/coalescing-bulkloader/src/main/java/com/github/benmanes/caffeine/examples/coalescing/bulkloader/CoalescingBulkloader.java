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
package com.github.benmanes.caffeine.examples.coalescing.bulkloader;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * An implementation of {@link AsyncCacheLoader} that delays fetching a bit until "enough" keys are collected
 * to do a bulk call. The assumption is that doing a bulk call is so much more efficient that it is worth
 * the wait.
 *
 * @param <Key>   the type of the key in the cache
 * @param <Value> the type of the value in the cache
 * @author complain to: guus@bloemsma.net
 */
public abstract class CoalescingBulkloader<Key, Value> implements AsyncCacheLoader<Key, Value> {
    private int maxLoadSize; // maximum number of keys to load in one call
    private long maxDelay; // maximum time between request of a value and loading it
    private volatile Queue<WaitingKey> waitingKeys = new ConcurrentLinkedQueue<>();
    private ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> schedule;
    // Queue.size() is expensive, so here we keep track of the queue size separately
    private AtomicInteger size = new AtomicInteger(0);

    private final class WaitingKey {
        Key key;
        CompletableFuture<Value> value;
        long waitingSince;
    }

    public CoalescingBulkloader(int maxLoadSize, long maxDelay) {
        assert maxLoadSize > 0;
        assert maxDelay > 0;
        this.maxLoadSize = maxLoadSize;
        this.maxDelay = maxDelay;
    }

    @Override public @NonNull CompletableFuture<Value> asyncLoad(@NonNull Key key, @NonNull Executor executor) {
        if (schedule == null || schedule.isDone()) {
            startWaiting();
        }

        final WaitingKey waitingKey = new WaitingKey();
        waitingKey.key = key;
        waitingKey.value = new CompletableFuture<>();
        waitingKey.waitingSince = System.currentTimeMillis();
        waitingKeys.add(waitingKey);

        if (size.incrementAndGet() >= maxLoadSize) {
            doLoad();
        }
        return waitingKey.value;
    }

    synchronized private void startWaiting() {
        schedule = timer.schedule(this::doLoad, maxDelay, MILLISECONDS);
    }

    synchronized private void doLoad() {
        schedule.cancel(false);
        do {
            Map<Key, CompletableFuture<Value>> toLoad = new HashMap<>(Math.min(size.get(), maxLoadSize));
            int counter = maxLoadSize;
            while (counter > 0) {
                final WaitingKey waitingKey = waitingKeys.poll();
                if (waitingKey == null)
                    break;
                else {
                    toLoad.put(waitingKey.key, waitingKey.value);
                    counter--;
                }
            }

            final int taken = maxLoadSize - counter;
            if (taken > 0) {
                load(toLoad);
                size.updateAndGet(oldSize -> oldSize - taken);
            }

        } while (size.get() >= maxLoadSize);
        final WaitingKey nextWaitingKey = waitingKeys.peek();
        if (nextWaitingKey != null) {
            schedule = timer.schedule(this::doLoad, nextWaitingKey.waitingSince + maxDelay - System.currentTimeMillis(), MILLISECONDS);
        }
    }

    /**
     * Must be implemented by user. It is expected to <b>asynchronously</b> load all keys and complete the corresponding futures
     * with the correct values. When done each future <b>must</b> be completed with the correct value.
     */
    protected abstract void load(Map<Key, CompletableFuture<Value>> toLoad);

}
