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
import static com.github.benmanes.caffeine.jcache.JCacheFixture.nullRef;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;
import static javax.cache.event.EventType.CREATED;
import static javax.cache.event.EventType.EXPIRED;
import static javax.cache.event.EventType.REMOVED;
import static javax.cache.event.EventType.UPDATED;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

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
import javax.cache.event.EventType;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.integration.CacheWriter;
import javax.cache.integration.CacheWriterException;
import javax.cache.processor.EntryProcessorException;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import com.github.benmanes.caffeine.jcache.CacheProxy;
import com.github.benmanes.caffeine.jcache.Expirable;
import com.github.benmanes.caffeine.jcache.JCacheFixture;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
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

    // Distinct config instances that are field-equal dedupe to one registration: register_twice
    // registers a single reference and cannot distinguish between field / identity equality.
    assertThat(first).isNotSameInstanceAs(second);
    assertThat(first).isEqualTo(second);

    dispatcher.register(first);
    dispatcher.register(second);
    assertThat(dispatcher.dispatchQueues).hasSize(1);
  }

  @Test
  void register_duplicateRegistration_closesTheDroppedListener() {
    // the dedupe drops the second listener, which close() then cannot reach
    var created = new ArrayList<CloseableCreatedListener>();
    Factory<CacheEntryListener<? super Integer, ? super Integer>> factory = () -> {
      var listener = new CloseableCreatedListener();
      created.add(listener);
      return listener;
    };
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    dispatcher.register(new MutableCacheEntryListenerConfiguration<>(factory,
        /* filterFactory= */ null, /* isOldValueRequired= */ false, /* isSynchronous= */ false));
    dispatcher.register(new MutableCacheEntryListenerConfiguration<>(factory,
        /* filterFactory= */ null, /* isOldValueRequired= */ false, /* isSynchronous= */ false));

    assertThat(dispatcher.dispatchQueues).hasSize(1);
    assertThat(created).hasSize(2);
    assertThat(created.get(1).closed).isTrue();
  }

  /** A listener that records whether it was closed. */
  private static final class CloseableCreatedListener
      implements CacheEntryCreatedListener<Integer, Integer>, AutoCloseable {
    boolean closed;

    @Override public void onCreated(
        Iterable<CacheEntryEvent<? extends Integer, ? extends Integer>> events) {}
    @Override public void close() {
      closed = true;
    }
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
  void awaitSynchronous_listenerError() {
    // an Error escaping a listener is rethrown as-is rather than wrapped
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    var future = new CompletableFuture<@Nullable CacheEntryListenerException>();
    var error = new AssertionError();
    future.completeExceptionally(error);
    dispatcher.pending.get().add(future);

    var e = assertThrows(AssertionError.class, dispatcher::awaitSynchronous);
    assertThat(e).isSameInstanceAs(error);
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
  void awaitSynchronous_listenerExceptions_sameInstance() {
    // A listener that rethrows a stored exception yields the same instance for both events, which
    // Throwable.addSuppressed rejects as self-suppression
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    var thrown = new CacheEntryListenerException("listener");
    dispatcher.pending.get().add(CompletableFuture.completedFuture(thrown));
    dispatcher.pending.get().add(CompletableFuture.completedFuture(thrown));

    var e = assertThrows(CacheEntryListenerException.class, dispatcher::awaitSynchronous);
    assertThat(e).isSameInstanceAs(thrown);
    assertThat(e.getSuppressed()).isEmpty();
    assertThat(dispatcher.pending.get()).isEmpty();
  }

  @Test
  void chainSynchronous_readsSnapshotAfterClear() {
    // The future completes only after chainSynchronous drained `pending`; the deferred thenApply must
    // read the captured snapshot, not the now-cleared (and possibly repopulated) ThreadLocal list
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    var thrown = new CacheEntryListenerException("listener");
    var future = new CompletableFuture<@Nullable CacheEntryListenerException>();
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
      // the listener failure is observed after the compute returns, so the mutation is committed
      assertThat(cache.get(KEY_1)).isEqualTo(VALUE_1);
    }
  }

  @ParameterizedTest
  @MethodSource("committedMutationOperations")
  void synchronousListenerFailure_committedMutationRetainsStatistics(
      Consumer<Cache<Integer, Integer>> prepare, Consumer<Cache<Integer, Integer>> operation,
      @Nullable Integer expectedValue, EventType expectedEvent,
      long hits, long misses, long puts, long removals) {
    var fixtureBuilder = JCacheFixture.builder().configure(config -> {
      config.setExecutorFactory(MoreExecutors::directExecutor);
      config.setStatisticsEnabled(true);
    });
    try (var fixture = fixtureBuilder.build();
         var cache = fixture.jcache()) {
      prepare.accept(cache);
      var listener = new ThrowingMutationListener();
      cache.registerCacheEntryListener(new MutableCacheEntryListenerConfiguration<>(
          () -> listener, /* filterFactory= */ null,
          /* isOldValueRequired= */ false, /* isSynchronous= */ true));
      var statistics = JCacheFixture.getStatistics(cache);
      long initialHits = statistics.getCacheHits();
      long initialMisses = statistics.getCacheMisses();
      long initialPuts = statistics.getCachePuts();
      long initialRemovals = statistics.getCacheRemovals();

      assertThrows(CacheEntryListenerException.class, () -> operation.accept(cache));

      assertThat(statistics.getCacheHits() - initialHits).isEqualTo(hits);
      assertThat(statistics.getCacheMisses() - initialMisses).isEqualTo(misses);
      assertThat(statistics.getCachePuts() - initialPuts).isEqualTo(puts);
      assertThat(statistics.getCacheRemovals() - initialRemovals).isEqualTo(removals);
      assertThat(listener.eventTypes).containsExactly(expectedEvent);
      assertThat(cache.get(KEY_1)).isEqualTo(expectedValue);
    }
  }

  static Stream<Arguments> committedMutationOperations() {
    Consumer<Cache<Integer, Integer>> empty = cache -> {};
    Consumer<Cache<Integer, Integer>> present = cache -> cache.put(KEY_1, VALUE_1);
    return Stream.of(
        argumentSet("put create", empty, (Consumer<Cache<Integer, Integer>>)
            cache -> cache.put(KEY_1, VALUE_1), VALUE_1, CREATED, 0L, 0L, 1L, 0L),
        argumentSet("getAndPut create", empty, (Consumer<Cache<Integer, Integer>>)
            cache -> cache.getAndPut(KEY_1, VALUE_1), VALUE_1, CREATED, 0L, 1L, 1L, 0L),
        argumentSet("putIfAbsent create", empty, (Consumer<Cache<Integer, Integer>>)
            cache -> cache.putIfAbsent(KEY_1, VALUE_1), VALUE_1, CREATED, 0L, 1L, 1L, 0L),
        argumentSet("put update", present, (Consumer<Cache<Integer, Integer>>)
            cache -> cache.put(KEY_1, VALUE_2), VALUE_2, UPDATED, 0L, 0L, 1L, 0L),
        argumentSet("getAndPut update", present, (Consumer<Cache<Integer, Integer>>)
            cache -> cache.getAndPut(KEY_1, VALUE_2), VALUE_2, UPDATED, 1L, 0L, 1L, 0L),
        argumentSet("replace", present, (Consumer<Cache<Integer, Integer>>)
            cache -> cache.replace(KEY_1, VALUE_2), VALUE_2, UPDATED, 1L, 0L, 1L, 0L),
        argumentSet("conditional replace", present, (Consumer<Cache<Integer, Integer>>)
            cache -> cache.replace(KEY_1, VALUE_1, VALUE_2),
            VALUE_2, UPDATED, 1L, 0L, 1L, 0L),
        argumentSet("getAndReplace", present, (Consumer<Cache<Integer, Integer>>)
            cache -> cache.getAndReplace(KEY_1, VALUE_2), VALUE_2, UPDATED, 1L, 0L, 1L, 0L),
        argumentSet("remove", present, (Consumer<Cache<Integer, Integer>>)
            cache -> cache.remove(KEY_1), null, REMOVED, 0L, 0L, 0L, 1L),
        argumentSet("conditional remove", present, (Consumer<Cache<Integer, Integer>>)
            cache -> cache.remove(KEY_1, VALUE_1), null, REMOVED, 1L, 0L, 0L, 1L),
        argumentSet("getAndRemove", present, (Consumer<Cache<Integer, Integer>>)
            cache -> cache.getAndRemove(KEY_1), null, REMOVED, 1L, 0L, 0L, 1L),
        argumentSet("iterator remove", present, (Consumer<Cache<Integer, Integer>>) cache -> {
          var iterator = cache.iterator();
          iterator.next();
          iterator.remove();
        }, null, REMOVED, 1L, 0L, 0L, 1L),
        argumentSet("putAll create", empty, (Consumer<Cache<Integer, Integer>>)
            cache -> cache.putAll(Map.of(KEY_1, VALUE_1)),
            VALUE_1, CREATED, 0L, 0L, 1L, 0L),
        argumentSet("putAll update", present, (Consumer<Cache<Integer, Integer>>)
            cache -> cache.putAll(Map.of(KEY_1, VALUE_2)),
            VALUE_2, UPDATED, 0L, 0L, 1L, 0L),
        argumentSet("removeAll", present, (Consumer<Cache<Integer, Integer>>)
            cache -> cache.removeAll(Set.of(KEY_1)), null, REMOVED, 0L, 0L, 0L, 1L),
        argumentSet("invoke create", empty, (Consumer<Cache<Integer, Integer>>) cache ->
            cache.invoke(KEY_1, (entry, arguments) -> {
              entry.setValue(VALUE_1);
              return null;
            }), VALUE_1, CREATED, 0L, 1L, 1L, 0L),
        argumentSet("invoke update", present, (Consumer<Cache<Integer, Integer>>) cache ->
            cache.invoke(KEY_1, (entry, arguments) -> {
              entry.setValue(VALUE_2);
              return null;
            }), VALUE_2, UPDATED, 1L, 0L, 1L, 0L),
        argumentSet("invoke remove", present, (Consumer<Cache<Integer, Integer>>) cache ->
            cache.invoke(KEY_1, (entry, arguments) -> {
              entry.remove();
              return null;
            }), null, REMOVED, 1L, 0L, 0L, 1L));
  }

  @Test
  void synchronousExpiredListenerFailure_retainsGetStatistics() {
    var fixtureBuilder = syncListenerFixture(new ThrowingMutationListener(), config -> {
      config.setStatisticsEnabled(true);
      config.setExpiryPolicyFactory(CreatedExpiryPolicy.factoryOf(Duration.ONE_MINUTE));
      config.setExpireAfterWrite(OptionalLong.of(TimeUnit.HOURS.toNanos(1)));
    });
    try (var fixture = fixtureBuilder.build();
         var cache = fixture.jcache()) {
      assertThrows(CacheEntryListenerException.class, () -> cache.put(KEY_1, VALUE_1));
      fixture.ticker().advance(java.time.Duration.ofMinutes(2));
      var statistics = JCacheFixture.getStatistics(cache);
      long misses = statistics.getCacheMisses();
      long evictions = statistics.getCacheEvictions();

      assertThrows(CacheEntryListenerException.class, () -> cache.get(KEY_1));

      assertThat(statistics.getCacheMisses()).isEqualTo(misses + 1);
      assertThat(statistics.getCacheEvictions()).isEqualTo(evictions + 1);
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
      // the listener failure is observed after the compute returns, so the mutation is committed
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
  void putAll_copierFails_abortsBeforeAnyCommit() {
    // The store-by-value copies are taken before the writer and the store loop, so a copier failure
    // commits nothing and leaves no synchronous future to leak to the thread's next operation
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
      var entries = new LinkedHashMap<Integer, Integer>();
      entries.put(KEY_1, VALUE_1);
      entries.put(KEY_2, VALUE_2);
      assertThrows(CacheException.class, () -> cache.putAll(entries));

      assertThat(cache.containsKey(KEY_1)).isFalse();
      assertThat(JCacheFixture.getDispatcher(cache).pending.get()).isEmpty();
    }
  }

  @Test
  void awaitSynchronous_dispatchFailed_listenerException() {
    // a dispatch that completes exceptionally, rather than returning the failure, still surfaces
    // the listener's own exception instead of wrapping it a second time
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    var future = new CompletableFuture<@Nullable CacheEntryListenerException>();
    var thrown = new CacheEntryListenerException("listener");
    future.completeExceptionally(thrown);
    dispatcher.pending.get().add(future);

    var e = assertThrows(CacheEntryListenerException.class, dispatcher::awaitSynchronous);
    assertThat(e).isSameInstanceAs(thrown);
    assertThat(dispatcher.pending.get()).isEmpty();
  }

  @Test
  void invoke_syncListenerThrows_propagatesToCaller() {
    var failure = new IllegalStateException("listener");
    CacheEntryCreatedListener<Integer, Integer> listener = events -> {
      throw failure;
    };
    try (var fixture = syncListenerFixture(listener).build();
         var cache = fixture.jcache()) {
      // the processor succeeded, so the listener's failure is what reaches the caller
      var e = assertThrows(CacheEntryListenerException.class, () ->
          cache.invoke(KEY_1, (entry, arguments) -> {
            entry.setValue(VALUE_1);
            return nullRef();
          }));
      assertThat(e).hasCauseThat().isSameInstanceAs(failure);
      assertThat(cache.get(KEY_1)).isEqualTo(VALUE_1);
    }
  }

  @Test
  void invoke_expiredAndProcessorThrows_retainsListenerFailure() {
    var listenerFailure = new IllegalStateException("listener");
    var processorFailure = new IllegalStateException("processor");
    CacheEntryExpiredListener<Integer, Integer> listener = events -> {
      throw listenerFailure;
    };
    var jcacheFixture = syncListenerFixture(listener, config -> {
      config.setExpiryPolicyFactory(() -> new CreatedExpiryPolicy(Duration.ONE_MINUTE));
      // a long native expiry keeps the entry resident so that invoke reconciles it lazily,
      // rather than the eviction listener publishing EXPIRED before the processor runs
      config.setExpireAfterWrite(OptionalLong.of(TimeUnit.HOURS.toNanos(1)));
    });
    try (var fixture = jcacheFixture.build();
         var cache = fixture.jcache()) {
      cache.put(KEY_1, VALUE_1);
      fixture.ticker().advance(java.time.Duration.ofMinutes(2));

      // the lazily expired prior is reconciled, firing the throwing listener, before the processor
      // runs and throws; the operation's own failure is preferred and the listener's is retained
      var e = assertThrows(EntryProcessorException.class, () ->
          cache.invoke(KEY_1, (entry, arguments) -> {
            throw processorFailure;
          }));
      assertThat(e).hasCauseThat().isSameInstanceAs(processorFailure);
      assertThat(e.getSuppressed()).hasLength(1);
    }
  }

  @Test
  void getAll_copierThrows_retainsPrimaryFailure() {
    // An expired entry publishes EXPIRED to a failing synchronous listener while copying the
    // surviving entries fails. The copier's failure is the caller's, with the listener's retained
    // as suppressed, where a bare finally would let the listener's replace it.
    var listenerFailure = new IllegalStateException("listener");
    CacheEntryExpiredListener<Integer, Integer> listener = events -> {
      throw listenerFailure;
    };
    var armed = new AtomicBoolean();
    var copier = new Copier() {
      @Override public <T> T copy(T object, ClassLoader classLoader) {
        if (armed.get() && Objects.equals(object, VALUE_2)) {
          throw new CacheException("copy failed");
        }
        return object;
      }
    };
    var fixtureBuilder = syncListenerFixture(listener, config -> {
      config.setStoreByValue(true);
      config.setCopierFactory(() -> copier);
      config.setExpiryPolicyFactory(CreatedExpiryPolicy.factoryOf(Duration.ONE_MINUTE));
      config.setExpireAfterWrite(OptionalLong.of(TimeUnit.HOURS.toNanos(1)));
    });
    try (var fixture = fixtureBuilder.build();
         var cache = fixture.jcache()) {
      cache.put(KEY_1, VALUE_1);
      fixture.ticker().advance(java.time.Duration.ofMinutes(2));
      cache.put(KEY_2, VALUE_2);
      armed.set(true);

      var e = assertThrows(CacheException.class, () -> cache.getAll(Set.of(KEY_1, KEY_2)));

      assertThat(e).hasMessageThat().isEqualTo("copy failed");
      assertThat(e.getSuppressed()).hasLength(1);
    }
  }

  @Test
  void putAll_storeFailsMidway_retainsListenerFailure() {
    var listenerFailure = new IllegalStateException("listener");
    CacheEntryCreatedListener<Integer, Integer> listener = events -> {
      throw listenerFailure;
    };
    var jcacheFixture = syncListenerFixture(listener, config -> {
      config.setMaximumWeight(OptionalLong.of(1_000));
      config.setWeigherFactory(Optional.of(() -> (key, value) -> {
        if (Objects.equals(value, VALUE_2)) {
          throw new IllegalStateException("weigher");
        }
        return 1;
      }));
    });
    try (var fixture = jcacheFixture.build();
         var cache = fixture.jcache()) {
      // KEY_1 commits and its listener throws; storing KEY_2 then aborts the loop. The store's
      // failure is the one the caller needs, with the listener's retained as suppressed. The
      // store-by-value copies are taken before the loop, so a copier failure cannot reach here.
      var entries = new LinkedHashMap<Integer, Integer>();
      entries.put(KEY_1, VALUE_1);
      entries.put(KEY_2, VALUE_2);

      var e = assertThrows(IllegalStateException.class, () -> cache.putAll(entries));
      assertThat(e).hasMessageThat().isEqualTo("weigher");
      assertThat(e.getSuppressed()).hasLength(1);
      assertThat(JCacheFixture.getDispatcher(cache).pending.get()).isEmpty();
    }
  }

  @Test
  void readThrough_syncListenerThrows_propagatesToCaller() {
    var failure = new IllegalStateException("listener");
    CacheEntryCreatedListener<Integer, Integer> listener = events -> {
      throw failure;
    };
    try (var fixture = syncListenerFixture(listener).build();
         var cache = fixture.jcacheLoading()) {
      // a read-through load publishes CREATED, so the listener's failure reaches the caller on
      // both the single-key and the bulk path
      var single = assertThrows(CacheEntryListenerException.class, () -> cache.get(KEY_1));
      assertThat(single).hasCauseThat().isSameInstanceAs(failure);

      var bulk = assertThrows(CacheEntryListenerException.class, () -> cache.getAll(Set.of(KEY_2)));
      assertThat(bulk).hasCauseThat().isSameInstanceAs(failure);
    }
  }

  /** Returns a fixture whose caches dispatch to the listener synchronously. */
  private static JCacheFixture.Builder syncListenerFixture(CacheEntryListener<Integer, Integer> l) {
    return syncListenerFixture(l, config -> {});
  }

  private static JCacheFixture.Builder syncListenerFixture(CacheEntryListener<Integer, Integer> l,
      Consumer<CaffeineConfiguration<Integer, Integer>> configurator) {
    return JCacheFixture.builder().configure(config -> {
      config.setExecutorFactory(MoreExecutors::directExecutor);
      config.addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
          /* listenerFactory= */ () -> l, /* filterFactory= */ null,
          /* isOldValueRequired= */ false, /* isSynchronous= */ true));
      configurator.accept(config);
    });
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

  /** A listener that fails every standard mutation notification. */
  private static final class ThrowingMutationListener
      implements CacheEntryCreatedListener<Integer, Integer>,
      CacheEntryUpdatedListener<Integer, Integer>, CacheEntryRemovedListener<Integer, Integer>,
      CacheEntryExpiredListener<Integer, Integer> {
    final Queue<EventType> eventTypes = new ConcurrentLinkedQueue<>();

    @Override public void onCreated(
        Iterable<CacheEntryEvent<? extends Integer, ? extends Integer>> events) {
      eventTypes.add(CREATED);
      throw new CacheEntryListenerException("created");
    }
    @Override public void onUpdated(
        Iterable<CacheEntryEvent<? extends Integer, ? extends Integer>> events) {
      eventTypes.add(UPDATED);
      throw new CacheEntryListenerException("updated");
    }
    @Override public void onRemoved(
        Iterable<CacheEntryEvent<? extends Integer, ? extends Integer>> events) {
      eventTypes.add(REMOVED);
      throw new CacheEntryListenerException("removed");
    }
    @Override public void onExpired(
        Iterable<CacheEntryEvent<? extends Integer, ? extends Integer>> events) {
      eventTypes.add(EXPIRED);
      throw new CacheEntryListenerException("expired");
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

  @Test
  void publish_listenerObservesTheCommittedMutation() {
    var onCreated = new AtomicReference<@Nullable Expirable<Integer>>();
    CacheEntryCreatedListener<Integer, Integer> listener = events -> {
      // read through the native cache, since the JCache API is refused to a callback
      @SuppressWarnings("unchecked")
      var nativeCache = (com.github.benmanes.caffeine.cache.Cache<Integer, Expirable<Integer>>)
          cacheRef.get().unwrap(com.github.benmanes.caffeine.cache.Cache.class);
      onCreated.set(nativeCache.getIfPresent(KEY_1));
    };
    try (var fixture = reentrantFixture(listener);
        var cache = fixture.jcache()) {
      cacheRef.set(cache);

      // the dispatch is staged until the computation commits, so the entry the event describes is
      // present when the listener runs rather than the state it replaced
      cache.put(KEY_1, VALUE_1);
      assertThat(onCreated.get()).isNotNull();
      assertThat(requireNonNull(onCreated.get()).get()).isEqualTo(VALUE_1);
    }
  }

  @Test
  void publish_listenerUsesTheCache_isRejected() {
    var failure = new AtomicReference<Throwable>();
    var armed = new AtomicBoolean(true);
    CacheEntryCreatedListener<Integer, Integer> listener = events -> {
      if (armed.compareAndSet(true, false)) {
        failure.set(assertThrows(IllegalStateException.class,
            () -> cacheRef.get().put(KEY_2, VALUE_2)));
      }
    };
    try (var fixture = reentrantFixture(listener);
        var cache = fixture.jcache()) {
      cacheRef.set(cache);

      // a caller-runs executor dispatches the listener on the publishing thread, so it is refused
      // like any other callback rather than being allowed to start a nested operation there
      cache.put(KEY_1, VALUE_1);
      assertThat(requireNonNull(failure.get()).getMessage()).isEqualTo("Recursive cache operation");
      assertThat(cache.containsKey(KEY_2)).isFalse();
    }
  }

  @Test
  void invoke_processorUsesTheCache_isRejected() {
    var failure = new AtomicReference<Throwable>();
    try (var fixture = reentrantFixture(events -> {});
        var cache = fixture.jcache()) {
      cacheRef.set(cache);

      // an entry processor runs inside the computation, unlike a listener, so it is still refused
      var result = cache.invoke(KEY_1, (entry, args) -> {
        failure.set(assertThrows(IllegalStateException.class, () -> cache.put(KEY_2, VALUE_2)));
        entry.setValue(VALUE_1);
        return null;
      });
      assertThat(requireNonNull(failure.get()).getMessage()).isEqualTo("Recursive cache operation");
      assertThat(cache.get(KEY_1)).isEqualTo(VALUE_1);
      assertThat(cache.containsKey(KEY_2)).isFalse();
      assertThat(result).isNull();
    }
  }

  @Test
  void publish_computationThrowsAfterPublishing_doesNotWedgeTheKey() {
    var weigherFails = new AtomicBoolean();
    var events = new ArrayList<Integer>();
    CacheEntryCreatedListener<Integer, Integer> listener =
        published -> published.forEach(event -> events.add(event.getValue()));
    var listenerConfig = new MutableCacheEntryListenerConfiguration<>(
        /* listenerFactory= */ () -> listener, /* filterFactory= */ null,
        /* isOldValueRequired= */ false, /* isSynchronous= */ true);
    try (var fixture = JCacheFixture.builder()
        .configure(config -> {
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.setMaximumWeight(OptionalLong.of(1_000));
          config.setWeigherFactory(Optional.of(() -> (key, value) -> {
            if (weigherFails.get()) {
              throw new IllegalStateException("weigher");
            }
            return 1;
          }));
          config.addCacheEntryListenerConfiguration(listenerConfig);
        }).build();
        var cache = fixture.jcache()) {
      // the weigher runs in the cache after the remapping function returns, so the event is
      // published and the store then aborts; the gate is released regardless or the key's dispatch
      // chain would never drain and every later event for it would queue behind it forever
      weigherFails.set(true);
      assertThrows(IllegalStateException.class, () -> cache.put(KEY_1, VALUE_1));

      weigherFails.set(false);
      assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> cache.put(KEY_1, VALUE_2));
      assertThat(cache.get(KEY_1)).isEqualTo(VALUE_2);
      assertThat(events).containsExactly(VALUE_1, VALUE_2).inOrder();
    }
  }

  private final AtomicReference<CacheProxy<Integer, Integer>> cacheRef = new AtomicReference<>();

  private static JCacheFixture reentrantFixture(CacheEntryCreatedListener<Integer, Integer> listener) {
    var listenerConfig = new MutableCacheEntryListenerConfiguration<>(
        /* listenerFactory= */ () -> listener, /* filterFactory= */ null,
        /* isOldValueRequired= */ false, /* isSynchronous= */ true);
    return JCacheFixture.builder()
        .configure(config -> {
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.addCacheEntryListenerConfiguration(listenerConfig);
        }).build();
  }

}
