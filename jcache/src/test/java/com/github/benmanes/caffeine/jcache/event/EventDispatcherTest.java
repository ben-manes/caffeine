/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.jcache.event;

import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;
import static javax.cache.event.EventType.CREATED;
import static javax.cache.event.EventType.EXPIRED;
import static javax.cache.event.EventType.REMOVED;
import static javax.cache.event.EventType.UPDATED;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.cache.Cache;
import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryCreatedListener;
import javax.cache.event.CacheEntryEvent;
import javax.cache.event.CacheEntryEventFilter;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.event.CacheEntryListener;
import javax.cache.event.CacheEntryRemovedListener;
import javax.cache.event.CacheEntryUpdatedListener;

import org.jspecify.annotations.Nullable;
import org.mockito.Mockito;
import org.testng.annotations.AfterTest;
import org.testng.annotations.Test;

import com.google.common.collect.Iterables;
import com.google.common.testing.EqualsTester;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class EventDispatcherTest {
  final ExecutorService executorService = Executors.newCachedThreadPool(
      new ThreadFactoryBuilder().setDaemon(true).build());

  @AfterTest
  public void afterTest() {
    executorService.shutdownNow();
  }

  @Test
  public void register_noListener() {
    var configuration = new MutableCacheEntryListenerConfiguration<Integer, Integer>(
        /* listenerFactory= */ null, /* filterFactory= */ null,
        /* isOldValueRequired= */ false, /* isSynchronous= */ false);
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    dispatcher.register(configuration);
    assertThat(dispatcher.dispatchQueues).isEmpty();
  }

  @Test
  public void register_twice() {
    CacheEntryCreatedListener<Integer, Integer> createdListener = Mockito.mock();
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    var configuration = new MutableCacheEntryListenerConfiguration<>(
        () -> createdListener, /* filterFactory= */ null,
        /* isOldValueRequired= */ false, /* isSynchronous= */ false);
    dispatcher.register(configuration);
    dispatcher.register(configuration);
    assertThat(dispatcher.dispatchQueues).hasSize(1);
  }

  @Test
  public void register_equality() {
    CacheEntryCreatedListener<Integer, Integer> createdListener = Mockito.mock();
    CacheEntryUpdatedListener<Integer, Integer> updatedListener = Mockito.mock();
    var c1 = new MutableCacheEntryListenerConfiguration<>(
        () -> createdListener, /* filterFactory= */ null,
        /* isOldValueRequired= */ false, /* isSynchronous= */ false);
    var c2 = new MutableCacheEntryListenerConfiguration<>(
        c1.getCacheEntryListenerFactory(), /* filterFactory= */ null,
        /* isOldValueRequired= */ false, /* isSynchronous= */ false);
    var c3 = new MutableCacheEntryListenerConfiguration<>(
        c1.getCacheEntryListenerFactory(), /* filterFactory= */ null,
        /* isOldValueRequired= */ true, /* isSynchronous= */ true);

    new EqualsTester()
        .addEqualityGroup(
            new Registration<>(c1, entry -> true, new EventTypeAwareListener<>(createdListener)),
            new Registration<>(c1, entry -> false, new EventTypeAwareListener<>(updatedListener)),
            new Registration<>(c2, entry -> true, new EventTypeAwareListener<>(createdListener)))
        .addEqualityGroup(c1, c2)
        .addEqualityGroup(
            new Registration<>(c3, entry -> true, new EventTypeAwareListener<>(createdListener)))
        .testEquals();
  }

  @Test
  public void deregister() {
    CacheEntryCreatedListener<Integer, Integer> createdListener = Mockito.mock();
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    var configuration = new MutableCacheEntryListenerConfiguration<>(
        () -> createdListener, /* filterFactory= */ null,
        /* isOldValueRequired= */ false, /* isSynchronous= */ false);
    dispatcher.register(configuration);
    dispatcher.deregister(configuration);
    assertThat(dispatcher.dispatchQueues).isEmpty();
  }

  @Test
  public void publishCreated() {
    CacheEntryCreatedListener<Integer, Integer> createdListener = Mockito.mock();
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      registerAll(dispatcher, List.of(createdListener));
      dispatcher.publishCreated(cache, 1, 2);
    }
    verify(createdListener, times(4)).onCreated(any());
    assertThat(dispatcher.pending.get()).hasSize(2);
    assertThat(dispatcher.dispatchQueues.values().stream()
        .flatMap(queue -> queue.entrySet().stream())).isEmpty();
  }

  @Test
  public void publishUpdated() {
    CacheEntryUpdatedListener<Integer, Integer> updatedListener = Mockito.mock();
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      registerAll(dispatcher, List.of(updatedListener));
      dispatcher.publishUpdated(cache, 1, 2, 3);
    }

    verify(updatedListener, times(4)).onUpdated(any());
    assertThat(dispatcher.pending.get()).hasSize(2);
    assertThat(dispatcher.dispatchQueues.values().stream()
        .flatMap(queue -> queue.entrySet().stream())).isEmpty();
  }

  @Test
  public void publishRemoved() {
    CacheEntryRemovedListener<Integer, Integer> removedListener = Mockito.mock();
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      registerAll(dispatcher, List.of(removedListener));
      dispatcher.publishRemoved(cache, 1, 2);
    }

    verify(removedListener, times(4)).onRemoved(any());
    assertThat(dispatcher.pending.get()).hasSize(2);
    assertThat(dispatcher.dispatchQueues.values().stream()
        .flatMap(queue -> queue.entrySet().stream())).isEmpty();
  }

  @Test
  public void publishExpired() {
    CacheEntryExpiredListener<Integer, Integer> expiredListener = Mockito.mock();
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      registerAll(dispatcher, List.of(expiredListener));
      dispatcher.publishExpired(cache, 1, 2);
    }

    verify(expiredListener, times(4)).onExpired(any());
    assertThat(dispatcher.pending.get()).hasSize(2);
    assertThat(dispatcher.dispatchQueues.values().stream()
        .flatMap(queue -> queue.entrySet().stream())).isEmpty();
  }

  @Test(invocationCount = 25)
  public void ordered() {
    var start = new AtomicBoolean();
    var next = new AtomicBoolean();
    var running = new AtomicBoolean();
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      Executor executor = task -> executorService.execute(() -> {
        running.set(true);
        if (!start.get()) {
          await().untilTrue(start);
        } else if (!next.get()) {
          await().untilTrue(next);
        }
        task.run();
      });
      var listener = new ConsumingCacheListener();
      var dispatcher = new EventDispatcher<Integer, Integer>(executor);
      dispatcher.register(new MutableCacheEntryListenerConfiguration<>(
          () -> listener, /* filterFactory= */ null,
          /* isOldValueRequired= */ false, /* isSynchronous= */ false));

      dispatcher.publishCreated(cache, 1, 2);
      dispatcher.publishUpdated(cache, 1, 2, 3);
      dispatcher.publishUpdated(cache, 1, 3, 4);
      dispatcher.publishRemoved(cache, 1, 5);
      dispatcher.publishExpired(cache, 1, 6);

      await().untilTrue(running);
      start.set(true);

      await().untilAsserted(() -> assertThat(listener.queue).hasSize(1));
      assertThat(dispatcher.dispatchQueues.values().stream()
          .flatMap(queue -> queue.entrySet().stream())).isNotEmpty();

      next.set(true);
      await().untilAsserted(() -> assertThat(dispatcher.dispatchQueues.values().stream()
          .flatMap(queue -> queue.entrySet().stream())).isEmpty());

      assertThat(listener.queue).hasSize(5);
      assertThat(listener.queue.stream().map(CacheEntryEvent::getKey))
          .containsExactly(1, 1, 1, 1, 1);
      assertThat(listener.queue.stream().map(CacheEntryEvent::getValue))
          .containsExactly(2, 3, 4, 5, 6).inOrder();
      assertThat(listener.queue.stream().map(CacheEntryEvent::getOldValue))
          .containsExactly(null, 2, 3, 5, 6).inOrder();
      assertThat(listener.queue.stream().map(CacheEntryEvent::getEventType))
          .containsExactly(CREATED, UPDATED, UPDATED, REMOVED, EXPIRED).inOrder();
    }
  }

  @Test(invocationCount = 25)
  public void parallel_keys() {
    var execute = new AtomicBoolean();
    var received1 = new AtomicBoolean();
    var received2 = new AtomicBoolean();
    var run1 = new AtomicBoolean();
    var run2 = new AtomicBoolean();
    var done1 = new AtomicBoolean();
    var done2 = new AtomicBoolean();
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      Executor executor = task -> executorService.execute(() -> {
        await().untilTrue(execute);
        task.run();
      });
      CacheEntryCreatedListener<Integer, Integer> listener = events -> {
        var event = requireNonNull(Iterables.getOnlyElement(events));
        if (event.getKey().equals(1)) {
          received1.set(true);
          await().untilTrue(run1);
          done1.set(true);
        } else if (event.getKey().equals(2)) {
          received2.set(true);
          await().untilTrue(run2);
          done2.set(true);
        }
      };

      var dispatcher = new EventDispatcher<Integer, Integer>(executor);
      dispatcher.register(new MutableCacheEntryListenerConfiguration<>(
          () -> listener, /* filterFactory= */ null,
          /* isOldValueRequired= */ false, /* isSynchronous= */ false));

      dispatcher.publishCreated(cache, 1, 1);
      dispatcher.publishCreated(cache, 2, 2);
      execute.set(true);

      await().untilTrue(received1);
      await().untilTrue(received2);

      run1.set(true);
      await().untilTrue(done1);

      run2.set(true);
      await().untilTrue(done2);
      await().untilAsserted(() -> assertThat(dispatcher.dispatchQueues.values().stream()
          .flatMap(queue -> queue.entrySet().stream())).isEmpty());
    }
  }

  @Test(invocationCount = 25)
  public void parallel_listeners() {
    var execute = new AtomicBoolean();
    var run = new AtomicBoolean();
    var done = new AtomicBoolean();
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      Executor executor = task -> executorService.execute(() -> {
        await().untilTrue(execute);
        task.run();
      });
      var consumer = new ConsumingCacheListener();
      CacheEntryCreatedListener<Integer, Integer> waiter = events -> {
        await().untilTrue(run);
        done.set(true);
      };

      var dispatcher = new EventDispatcher<Integer, Integer>(executor);
      dispatcher.register(new MutableCacheEntryListenerConfiguration<>(
          () -> consumer, /* filterFactory= */ null,
          /* isOldValueRequired= */ false, /* isSynchronous= */ false));
      dispatcher.register(new MutableCacheEntryListenerConfiguration<>(
          () -> waiter, /* filterFactory= */ null,
          /* isOldValueRequired= */ false, /* isSynchronous= */ false));

      dispatcher.publishCreated(cache, 1, 2);
      execute.set(true);

      await().untilAsserted(() -> assertThat(consumer.queue).hasSize(1));
      run.set(true);

      await().untilTrue(done);
      await().untilAsserted(() -> assertThat(dispatcher.dispatchQueues.values().stream()
          .flatMap(queue -> queue.entrySet().stream())).isEmpty());
    }
  }

  @Test
  public void awaitSynchronous() {
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    dispatcher.pending.get().add(CompletableFuture.completedFuture(null));
    dispatcher.awaitSynchronous();
    assertThat(dispatcher.pending.get()).isEmpty();
  }

  @Test
  public void awaitSynchronous_failure() {
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    @SuppressWarnings("NullAway")
    var future = new CompletableFuture<@Nullable Void>();
    future.completeExceptionally(new RuntimeException());
    dispatcher.pending.get().add(future);

    dispatcher.awaitSynchronous();
    assertThat(dispatcher.pending.get()).isEmpty();
  }

  @Test
  public void awaitSynchronous_nested() {
    var primary = new EventDispatcher<Integer, Integer>(Runnable::run);
    var secondary = new EventDispatcher<Integer, Integer>(Runnable::run);

    var pendingFutures = new ArrayList<>();
    CacheEntryCreatedListener<Integer, Integer> listener = events ->
        pendingFutures.addAll(secondary.pending.get());

    var configuration = new MutableCacheEntryListenerConfiguration<>(
        () -> listener, /* filterFactory= */ null,
        /* isOldValueRequired= */ false, /* isSynchronous= */ false);
    primary.register(configuration);
    int key = 1;

    var dispatchQueue = primary.dispatchQueues.values().iterator().next();
    @SuppressWarnings("NullAway")
    var queue = new CompletableFuture<@Nullable Void>();
    dispatchQueue.put(key, queue);

    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      primary.publishCreated(cache, key, -key);
      queue.complete(null);
    }
    assertThat(pendingFutures).isEmpty();
  }

  @Test
  public void ignoreSynchronous() {
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    dispatcher.pending.get().add(CompletableFuture.completedFuture(null));

    dispatcher.ignoreSynchronous();
    assertThat(dispatcher.pending.get()).isEmpty();
  }

  /**
   * Registers (listeners) * (2 synchronous modes) * (3 filter modes) configurations. For
   * simplicity, an event is published and ignored if the listener is of the wrong type. For a
   * single event, it should be consumed by (2 filter) * (2 synchronous) = 4 listeners where only
   * 2 are synchronous.
   */
  private static void registerAll(EventDispatcher<Integer, Integer> dispatcher,
      List<CacheEntryListener<Integer, Integer>> listeners) {
    CacheEntryEventFilter<Integer, Integer> rejectFilter = event -> false;
    CacheEntryEventFilter<Integer, Integer> allowFilter = event -> true;

    var isOldValueRequired = false;
    for (var listener : listeners) {
      for (boolean synchronous : List.of(true, false)) {
        dispatcher.register(new MutableCacheEntryListenerConfiguration<>(
            () -> listener, null, isOldValueRequired, synchronous));
        dispatcher.register(new MutableCacheEntryListenerConfiguration<>(
            () -> listener, () -> allowFilter, isOldValueRequired, synchronous));
        dispatcher.register(new MutableCacheEntryListenerConfiguration<>(
            () -> listener, () -> rejectFilter, isOldValueRequired, synchronous));
      }
    }
  }

  private static final class ConsumingCacheListener implements
      CacheEntryCreatedListener<Integer, Integer>,  CacheEntryUpdatedListener<Integer, Integer>,
      CacheEntryRemovedListener<Integer, Integer>, CacheEntryExpiredListener<Integer, Integer> {
    final Queue<CacheEntryEvent<?, ?>> queue = new ConcurrentLinkedQueue<>();

    @Override
    public void onCreated(Iterable<CacheEntryEvent<? extends Integer, ? extends Integer>> events) {
      Iterables.addAll(queue, events);
    }

    @Override
    public void onUpdated(Iterable<CacheEntryEvent<? extends Integer, ? extends Integer>> events) {
      Iterables.addAll(queue, events);
    }

    @Override
    public void onRemoved(Iterable<CacheEntryEvent<? extends Integer, ? extends Integer>> events) {
      Iterables.addAll(queue, events);
    }

    @Override
    public void onExpired(Iterable<CacheEntryEvent<? extends Integer, ? extends Integer>> events) {
      Iterables.addAll(queue, events);
    }
  }
}
