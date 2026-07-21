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

import static com.github.benmanes.caffeine.jcache.JCacheFixture.KEY_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.KEY_2;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_2;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.await;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;
import static javax.cache.event.EventType.CREATED;
import static javax.cache.event.EventType.EXPIRED;
import static javax.cache.event.EventType.REMOVED;
import static javax.cache.event.EventType.UPDATED;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.configuration.CacheEntryListenerConfiguration;
import javax.cache.configuration.Factory;
import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryCreatedListener;
import javax.cache.event.CacheEntryEvent;
import javax.cache.event.CacheEntryEventFilter;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.event.CacheEntryListener;
import javax.cache.event.CacheEntryListenerException;
import javax.cache.event.CacheEntryRemovedListener;
import javax.cache.event.CacheEntryUpdatedListener;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.integration.CacheWriter;
import javax.cache.integration.CacheWriterException;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

import com.github.benmanes.caffeine.jcache.JCacheFixture;
import com.github.benmanes.caffeine.jcache.copy.Copier;
import com.google.common.collect.Iterables;
import com.google.common.testing.EqualsTester;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@TestInstance(PER_CLASS)
final class EventDispatcherTest {
  @AutoClose("shutdown")
  private final ExecutorService executorService = Executors.newCachedThreadPool(
      new ThreadFactoryBuilder().setDaemon(true).build());

  @Test
  void register_noListener() {
    var configuration = new MutableCacheEntryListenerConfiguration<Integer, Integer>(
        /* listenerFactory= */ null, /* filterFactory= */ null,
        /* isOldValueRequired= */ false, /* isSynchronous= */ false);
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    dispatcher.register(configuration);
    assertThat(dispatcher.dispatchQueues).isEmpty();
  }

  @Test
  void register_twice() {
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
  void register_distinctButEqualConfigurations_dedupe() {
    CacheEntryCreatedListener<Integer, Integer> createdListener = Mockito.mock();
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    var first = new MutableCacheEntryListenerConfiguration<>(
        /* listenerFactory= */ () -> createdListener, /* filterFactory= */ null,
        /* isOldValueRequired= */ false, /* isSynchronous= */ false);
    var second = new MutableCacheEntryListenerConfiguration<>(
        first.getCacheEntryListenerFactory(), /* filterFactory= */ null,
        /* isOldValueRequired= */ false, /* isSynchronous= */ false);

    // Distinct config instances that are field-equal dedupe to one registration (the RI fires both).
    // register_twice registers a single reference and cannot distinguish field- from identity-equality.
    assertThat(first).isNotSameInstanceAs(second);
    assertThat(first).isEqualTo(second);

    dispatcher.register(first);
    dispatcher.register(second);
    assertThat(dispatcher.dispatchQueues).hasSize(1);
  }

  @Test
  void register_equality() {
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
  void deregister() {
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
  void deregister_classStrictCustomConfiguration() {
    CacheEntryCreatedListener<Integer, Integer> createdListener = Mockito.mock();
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    var configuration = new CustomCacheEntryListenerConfiguration<>(
        () -> createdListener, /* filterFactory= */ null,
        /* isOldValueRequired= */ false, /* isSynchronous= */ false);
    dispatcher.register(configuration);
    dispatcher.deregister(configuration);
    assertThat(dispatcher.dispatchQueues).isEmpty();
  }

  @Test
  void publishCreated() {
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
  void publishCreated_filterThrows() {
    CacheEntryCreatedListener<Integer, Integer> createdListener = Mockito.mock();
    CacheEntryEventFilter<Integer, Integer> filter = event -> {
      throw new CacheEntryListenerException("broken");
    };
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      dispatcher.register(new MutableCacheEntryListenerConfiguration<>(
          () -> createdListener, () -> filter,
          /* isOldValueRequired= */ false, /* isSynchronous= */ true));
      dispatcher.register(new MutableCacheEntryListenerConfiguration<>(
          () -> createdListener, /* filterFactory= */ null,
          /* isOldValueRequired= */ false, /* isSynchronous= */ true));

      // a filter failure must not abort the caller's cache operation or affect other listeners
      dispatcher.publishCreated(cache, 1, 2);
    }
    verify(createdListener).onCreated(any());
    assertThat(dispatcher.pending.get()).hasSize(1);
    assertThat(dispatcher.dispatchQueues.values().stream()
        .flatMap(queue -> queue.entrySet().stream())).isEmpty();
  }

  @Test
  void publishUpdated() {
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
  void publishUpdatedQuietly() {
    CacheEntryUpdatedListener<Integer, Integer> updatedListener = Mockito.mock();
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      registerAll(dispatcher, List.of(updatedListener));
      dispatcher.publishUpdatedQuietly(cache, 1, 2, 3);
    }

    verify(updatedListener, times(4)).onUpdated(any());
    assertThat(dispatcher.pending.get()).isEmpty();
    assertThat(dispatcher.dispatchQueues.values().stream()
        .flatMap(queue -> queue.entrySet().stream())).isEmpty();
  }

  @Test
  void publishRemoved() {
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
  void publishExpired() {
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

  @RepeatedTest(25)
  void ordered() {
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

  @RepeatedTest(25)
  void parallel_keys() {
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

  @RepeatedTest(25)
  void parallel_listeners() {
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
  void awaitSynchronous() {
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    dispatcher.pending.get().add(CompletableFuture.completedFuture(null));
    dispatcher.awaitSynchronous();
    assertThat(dispatcher.pending.get()).isEmpty();
  }

  @Test
  void awaitSynchronous_failure() {
    // an exceptionally-completed future (e.g. a rejecting executor)
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    var future = new CompletableFuture<@Nullable CacheEntryListenerException>();
    future.completeExceptionally(new RuntimeException());
    dispatcher.pending.get().add(future);

    assertThrows(CacheEntryListenerException.class, dispatcher::awaitSynchronous);
    assertThat(dispatcher.pending.get()).isEmpty();
  }

  @Test
  void awaitSynchronous_listenerException() {
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    var thrown = new CacheEntryListenerException("listener");
    dispatcher.pending.get().add(CompletableFuture.completedFuture(thrown));

    var e = assertThrows(CacheEntryListenerException.class, dispatcher::awaitSynchronous);
    assertThat(e).isSameInstanceAs(thrown);
    assertThat(dispatcher.pending.get()).isEmpty();
  }

  @Test
  void awaitSynchronous_listenerExceptions_suppressed() {
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    var first = new CacheEntryListenerException("first");
    var second = new CacheEntryListenerException("second");
    dispatcher.pending.get().add(CompletableFuture.completedFuture(first));
    dispatcher.pending.get().add(CompletableFuture.completedFuture(second));

    var e = assertThrows(CacheEntryListenerException.class, dispatcher::awaitSynchronous);
    assertThat(e).isSameInstanceAs(first);
    assertThat(e.getSuppressed()).asList().containsExactly(second);
    assertThat(dispatcher.pending.get()).isEmpty();
  }

  @Test
  void chainSynchronous_readsSnapshotAfterClear() {
    // The future completes only after chainSynchronous drained `pending`; the deferred thenApply must
    // read the captured snapshot, not the now-cleared (and possibly repopulated) ThreadLocal list
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    var thrown = new CacheEntryListenerException("listener");
    var future = new CompletableFuture<CacheEntryListenerException>();
    dispatcher.pending.get().add(future);

    var chain = dispatcher.chainSynchronous();
    assertThat(dispatcher.pending.get()).isEmpty();
    assertThat(chain.isDone()).isFalse();

    future.complete(thrown);
    assertThat(chain.join()).isSameInstanceAs(thrown);
  }

  @Test
  void put_syncListenerThrows_propagatesToCaller() {
    var failure = new IllegalStateException("boom");
    CacheEntryCreatedListener<Integer, Integer> listener = events -> {
      throw failure;
    };
    var jcacheFixture = JCacheFixture.builder()
        .configure(config -> {
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
              /* listenerFactory= */ () -> listener, /* filterFactory= */ null,
              /* isOldValueRequired= */ false, /* isSynchronous= */ true));
        });
    try (var fixture = jcacheFixture.build();
         var cache = fixture.jcache()) {
      // the synchronous listener's exception is wrapped and propagated to the caller
      var e = assertThrows(CacheEntryListenerException.class, () -> cache.put(KEY_1, VALUE_1));
      assertThat(e).hasCauseThat().isSameInstanceAs(failure);
      // but the mutation committed before the listener ran (spec: does not affect the update)
      assertThat(cache.get(KEY_1)).isEqualTo(VALUE_1);
    }
  }

  @Test
  void put_syncListenerThrowsError_notWrappedAsListenerException() {
    var failure = new AssertionError("boom");
    CacheEntryCreatedListener<Integer, Integer> listener = events -> {
      throw failure;
    };
    var jcacheFixture = JCacheFixture.builder()
        .configure(config -> {
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
              /* listenerFactory= */ () -> listener, /* filterFactory= */ null,
              /* isOldValueRequired= */ false, /* isSynchronous= */ true));
        });
    try (var fixture = jcacheFixture.build();
         var cache = fixture.jcache()) {
      // an Error is not wrapped as a CacheEntryListenerException; it propagates as-is
      var e = assertThrows(AssertionError.class, () -> cache.put(KEY_1, VALUE_1));
      assertThat(e).isSameInstanceAs(failure);
      // the mutation still committed before the listener ran (spec: does not affect the update)
      assertThat(cache.get(KEY_1)).isEqualTo(VALUE_1);
    }
  }

  @Test
  void publishCreated_asyncListenerThrows_swallowed() {
    var delivered = new boolean[1];
    CacheEntryCreatedListener<Integer, Integer> listener = events -> {
      delivered[0] = true;
      throw new IllegalStateException("boom");
    };
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      dispatcher.register(new MutableCacheEntryListenerConfiguration<>(
          /* listenerFactory= */ () -> listener, /* filterFactory= */ null,
          /* isOldValueRequired= */ false, /* isSynchronous= */ false));
      // an asynchronous listener's exception is logged, never propagated
      dispatcher.publishCreated(cache, 1, 2);
      dispatcher.awaitSynchronous();
    }
    assertThat(delivered[0]).isTrue();
    assertThat(dispatcher.pending.get()).isEmpty();
  }

  @Test
  void publishCreated_syncListenerThrows_subsequentEventStillDelivered() {
    var deliveries = new int[1];
    CacheEntryCreatedListener<Integer, Integer> listener = events -> {
      deliveries[0]++;
      throw new IllegalStateException("boom");
    };
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      dispatcher.register(new MutableCacheEntryListenerConfiguration<>(
          /* listenerFactory= */ () -> listener, /* filterFactory= */ null,
          /* isOldValueRequired= */ false, /* isSynchronous= */ true));

      dispatcher.publishCreated(cache, 1, 2);
      assertThrows(CacheEntryListenerException.class, dispatcher::awaitSynchronous);
      // a throwing listener must not break the per-key dispatch chain
      dispatcher.publishCreated(cache, 1, 3);
      assertThrows(CacheEntryListenerException.class, dispatcher::awaitSynchronous);
    }
    assertThat(deliveries[0]).isEqualTo(2);
  }

  @Test
  void putIfAbsent_abortedWrite_doesNotLeakPending() {
    CacheEntryExpiredListener<Integer, Integer> expiredListener = events -> {};
    CacheWriter<Integer, Integer> writer = Mockito.mock();
    var jcacheFixture = JCacheFixture.builder()
        .configure(config -> {
          config.setCacheWriterFactory(() -> writer);
          config.setWriteThrough(true);
          config.setExpiryPolicyFactory(
              CreatedExpiryPolicy.factoryOf(new Duration(TimeUnit.MINUTES, 1)));
          // a long native expiry keeps the jcache-expired entry physically present, so putIfAbsent
          // reaches its lazy expired-prior path where the pre-reorder publish/pend would occur
          config.setExpireAfterWrite(OptionalLong.of(TimeUnit.HOURS.toNanos(1)));
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
              /* listenerFactory= */ () -> expiredListener, /* filterFactory= */ null,
              /* isOldValueRequired= */ false, /* isSynchronous= */ true));
        });
    try (var fixture = jcacheFixture.build();
         var cache = fixture.jcache()) {
      cache.put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      // the reorder writes before publishing the expired prior, so a failing writer aborts before
      // any synchronous future is pended; a leak would strand it in the thread's next operation
      doThrow(CacheWriterException.class).when(writer).write(any());
      assertThrows(CacheWriterException.class, () -> cache.putIfAbsent(KEY_1, VALUE_2));

      assertThat(JCacheFixture.getDispatcher(cache).pending.get()).isEmpty();
    }
  }

  @Test
  void putAll_copierFailsMidway_doesNotLeakPending() {
    // A store-by-value copier that throws mid-loop must not skip awaitSynchronous for the entries
    // already committed -- their synchronous futures would otherwise leak to the thread's next op
    CacheEntryCreatedListener<Integer, Integer> createdListener = events -> {};
    var copier = new Copier() {
      @Override public <T> T copy(T object, ClassLoader classLoader) {
        if (Objects.equals(object, VALUE_2)) {
          throw new CacheException("copy failed");
        }
        return object;
      }
    };
    var jcacheFixture = JCacheFixture.builder()
        .configure(config -> {
          config.setStoreByValue(true);
          config.setCopierFactory(() -> copier);
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
              /* listenerFactory= */ () -> createdListener, /* filterFactory= */ null,
              /* isOldValueRequired= */ false, /* isSynchronous= */ true));
        });
    try (var fixture = jcacheFixture.build();
         var cache = fixture.jcache()) {
      // KEY_1 commits and pends its CREATED future; KEY_2's value copy throws mid-loop.
      var entries = new LinkedHashMap<Integer, Integer>();
      entries.put(KEY_1, VALUE_1);
      entries.put(KEY_2, VALUE_2);
      assertThrows(CacheException.class, () -> cache.putAll(entries));
      assertThat(JCacheFixture.getDispatcher(cache).pending.get()).isEmpty();
    }
  }

  @Test
  void awaitSynchronous_nested() {
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
    var queue = new CompletableFuture<@Nullable CacheEntryListenerException>();
    dispatchQueue.put(key, queue);

    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      primary.publishCreated(cache, key, -key);
      queue.complete(null);
    }
    assertThat(pendingFutures).isEmpty();
  }

  @Test
  void ignoreSynchronous() {
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

    boolean isOldValueRequired = false;
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

  private static final class CustomCacheEntryListenerConfiguration<K, V>
      implements CacheEntryListenerConfiguration<K, V> {
    private static final long serialVersionUID = 1L;

    private final @Nullable Factory<CacheEntryEventFilter<? super K, ? super V>> filterFactory;
    private final @Nullable Factory<CacheEntryListener<? super K, ? super V>> listenerFactory;
    private final boolean oldValueRequired;
    private final boolean synchronous;

    CustomCacheEntryListenerConfiguration(
        @Nullable Factory<CacheEntryListener<? super K, ? super V>> listenerFactory,
        @Nullable Factory<CacheEntryEventFilter<? super K, ? super V>> filterFactory,
        boolean isOldValueRequired, boolean isSynchronous) {
      this.oldValueRequired = isOldValueRequired;
      this.listenerFactory = listenerFactory;
      this.filterFactory = filterFactory;
      this.synchronous = isSynchronous;
    }
    @Override public boolean isSynchronous() {
      return synchronous;
    }
    @Override public boolean isOldValueRequired() {
      return oldValueRequired;
    }
    @Override public @Nullable Factory<CacheEntryListener<? super K, ? super V>>
    getCacheEntryListenerFactory() {
      return listenerFactory;
    }
    @Override public @Nullable Factory<CacheEntryEventFilter<? super K, ? super V>>
    getCacheEntryEventFilterFactory() {
      return filterFactory;
    }
    @Override public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      } else if (!(o instanceof CustomCacheEntryListenerConfiguration<?, ?>)) {
        return false;
      }
      var other = (CustomCacheEntryListenerConfiguration<?, ?>) o;
      return (synchronous == other.synchronous)
          && (oldValueRequired == other.oldValueRequired)
          && Objects.equals(filterFactory, other.filterFactory)
          && Objects.equals(listenerFactory, other.listenerFactory);
    }
    @Override public int hashCode() {
      return Objects.hash(listenerFactory, filterFactory, oldValueRequired, synchronous);
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
