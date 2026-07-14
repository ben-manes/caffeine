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
package com.github.benmanes.caffeine.jcache.integration;

import static com.github.benmanes.caffeine.jcache.JCacheFixture.ENTRIES;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.KEY_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.KEY_2;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_2;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.getStatistics;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.nullRef;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.integration.CacheWriter;
import javax.cache.integration.CacheWriterException;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.EntryProcessorException;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import com.github.benmanes.caffeine.jcache.EntryProxy;
import com.github.benmanes.caffeine.jcache.JCacheFixture;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class CacheWriterTest {

  private static JCacheFixture jcacheFixture(CloseableCacheWriter writer) {
    return JCacheFixture.builder()
        .configure(config -> {
          config.setCacheWriterFactory(() -> writer);
          config.setWriteThrough(true);
        }).build();
  }

  private static JCacheFixture statisticsFixture(CloseableCacheWriter writer) {
    return JCacheFixture.builder()
        .configure(config -> {
          config.setCacheWriterFactory(() -> writer);
          config.setWriteThrough(true);
          config.setStatisticsEnabled(true);
        }).build();
  }

  @Test
  void put() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().put(KEY_1, VALUE_1);
      verify(writer).write(new EntryProxy<>(KEY_1, VALUE_1));
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void put_fails() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      doThrow(CacheWriterException.class).when(writer).write(any());
      assertThrows(CacheWriterException.class, () -> fixture.jcache().put(KEY_1, VALUE_1));
    }
  }

  @Test
  void putAll() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().putAll(ENTRIES);

      var entries = toCacheEntries(ENTRIES);
      verify(writer).writeAll(entries);
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void putAll_empty() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().putAll(Map.of());
      verifyNoInteractions(writer);
    }
  }

  @Test
  void putAll_fails() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      doThrow(CacheWriterException.class).when(writer).writeAll(any());
      var map = new HashMap<Integer, Integer>();
      map.put(KEY_1, VALUE_1);

      assertThrows(CacheWriterException.class, () -> fixture.jcache().putAll(map));
      assertThat(map).containsExactly(KEY_1, VALUE_1);
    }
  }

  @Test
  void putAll_fails_immutable() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      doThrow(CacheWriterException.class).when(writer).writeAll(any());
      var map = Map.of(KEY_1, VALUE_1);

      assertThrows(CacheWriterException.class, () -> fixture.jcache().putAll(map));
      assertThat(map).containsExactly(KEY_1, VALUE_1);
    }
  }

  @Test
  void putIfAbsent_expiredPrior_failingWriter_singleExpiry() {
    var expiredCount = new AtomicInteger();
    CacheEntryExpiredListener<Integer, Integer> expiredListener =
        events -> events.forEach(event -> expiredCount.incrementAndGet());
    CacheWriter<Integer, Integer> writer = Mockito.mock();
    var jcacheFixture = JCacheFixture.builder()
        .configure(config -> {
          config.setCacheWriterFactory(() -> writer);
          config.setWriteThrough(true);
          config.setStatisticsEnabled(true);
          config.setExpiryPolicyFactory(
              CreatedExpiryPolicy.factoryOf(new Duration(TimeUnit.MINUTES, 1)));
          // a long native expiry keeps the jcache-expired entry physically present, so putIfAbsent
          // exercises its own lazy-expiry path rather than seeing a natively-reaped absent entry
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

      // the failed putIfAbsent must not publish or evict the expired prior; only the follow-up get
      // reaps it, so exactly one EXPIRED event and one eviction occur for the single expiration
      doThrow(CacheWriterException.class).when(writer).write(any());
      assertThrows(CacheWriterException.class, () -> cache.putIfAbsent(KEY_1, VALUE_2));

      assertThat(cache.get(KEY_1)).isNull();
      assertThat(expiredCount.get()).isEqualTo(1);
      assertThat(getStatistics(cache).getCacheEvictions()).isEqualTo(1L);
    }
  }

  @Test
  void putIfAbsent_nonSerializableValue_doesNotWrite() {
    CacheWriter<Integer, Object> writer = Mockito.mock();
    var config = new MutableConfiguration<Integer, Object>()
        .setStoreByValue(true)
        .setWriteThrough(true)
        .setCacheWriterFactory(() -> writer);
    try (var fixture = JCacheFixture.builder().build();
         var cache = fixture.cacheManager().createCache("non-serializable", config)) {
      // copyOf(value) fails before the writer runs, so a failed store-by-value putIfAbsent leaves
      // the write-through store untouched
      assertThrows(CacheException.class,
          () -> cache.putIfAbsent(KEY_1, new Object()));
      verifyNoInteractions(writer);
    }
  }

  @ParameterizedTest
  @MethodSource("invokeExpiredPriorOps")
  void invoke_expiredPrior_failingWriter_singleExpiry(
      EntryProcessor<Integer, Integer, @Nullable Void> processor) {
    var expiredCount = new AtomicInteger();
    CacheEntryExpiredListener<Integer, Integer> expiredListener =
        events -> events.forEach(event -> expiredCount.incrementAndGet());
    CacheWriter<Integer, Integer> writer = Mockito.mock();
    var jcacheFixture = JCacheFixture.builder()
        .configure(config -> {
          config.setCacheWriterFactory(() -> writer);
          config.setWriteThrough(true);
          config.setStatisticsEnabled(true);
          config.setExpiryPolicyFactory(
              CreatedExpiryPolicy.factoryOf(new Duration(TimeUnit.MINUTES, 1)));
          // a long native expiry keeps the jcache-expired entry physically present, so invoke
          // exercises its lazy expired-prior path rather than seeing a natively-reaped absent entry
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

      // the expired prior is published and its eviction counted before the processor runs, and its
      // removal commits even though the writer then fails, so the follow-up get finds nothing left
      // to reap: exactly one EXPIRED event and one eviction for the single expiration
      doThrow(CacheWriterException.class).when(writer).write(any());
      doThrow(CacheWriterException.class).when(writer).delete(any());
      assertThrows(EntryProcessorException.class,
          () -> cache.<@Nullable Void>invoke(KEY_1, processor));

      assertThat(cache.get(KEY_1)).isNull();
      assertThat(expiredCount.get()).isEqualTo(1);
      assertThat(getStatistics(cache).getCacheEvictions()).isEqualTo(1L);
    }
  }

  static Stream<Arguments> invokeExpiredPriorOps() {
    EntryProcessor<Integer, Integer, @Nullable Void> setValue = (entry, args) -> {
      entry.setValue(VALUE_2);
      return nullRef();
    };
    EntryProcessor<Integer, Integer, @Nullable Void> remove = (entry, args) -> {
      entry.remove();
      return nullRef();
    };
    return Stream.of(
        arguments(named("setValue", setValue)),
        arguments(named("remove", remove)));
  }

  @Test
  void replace() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().put(KEY_1, VALUE_1);
      assertThat(fixture.jcache().replace(KEY_1, VALUE_2)).isTrue();

      verify(writer).write(new EntryProxy<>(KEY_1, VALUE_1));
      verify(writer).write(new EntryProxy<>(KEY_1, VALUE_2));
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void replace_fails() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().put(KEY_1, VALUE_1);

      doThrow(CacheWriterException.class).when(writer).write(any());
      assertThrows(CacheWriterException.class, () ->
          fixture.jcache().replace(KEY_1, VALUE_2));
    }
  }

  @Test
  void replaceConditionally() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().put(KEY_1, VALUE_1);
      assertThat(fixture.jcache().replace(KEY_1, VALUE_1, VALUE_2)).isTrue();

      verify(writer).write(new EntryProxy<>(KEY_1, VALUE_1));
      verify(writer).write(new EntryProxy<>(KEY_1, VALUE_2));
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void replaceConditionally_fails() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().put(KEY_1, VALUE_1);

      doThrow(CacheWriterException.class).when(writer).write(any());
      assertThrows(CacheWriterException.class, () ->
          fixture.jcache().replace(KEY_1, VALUE_1, VALUE_2));
    }
  }

  @Test
  void remove() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().put(KEY_1, VALUE_1);
      assertThat(fixture.jcache().remove(KEY_1)).isTrue();

      verify(writer).write(new EntryProxy<>(KEY_1, VALUE_1));
      verify(writer).delete(KEY_1);
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void remove_fails() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().put(KEY_1, VALUE_1);

      doThrow(CacheWriterException.class).when(writer).delete(any());
      assertThrows(CacheWriterException.class, () -> fixture.jcache().remove(KEY_1));
    }
  }

  @Test
  void removeConditionally() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.jcache().remove(KEY_1, VALUE_1);

      verify(writer).write(new EntryProxy<>(KEY_1, VALUE_1));
      verify(writer).delete(KEY_1);
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void removeConditionally_fails() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().put(KEY_1, VALUE_1);

      doThrow(CacheWriterException.class).when(writer).delete(any());
      assertThrows(CacheWriterException.class, () -> fixture.jcache().remove(KEY_1, VALUE_1));
    }
  }

  @Test
  void invoke_loadThenRemove_writesDelete() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      // getValue() reads through the loader for the absent key; the subsequent remove() makes the
      // dominant action DELETED, so the loaded entry is written through as a delete and discarded
      var result = fixture.jcacheLoading().invoke(KEY_1, (entry, args) -> {
        entry.getValue();
        entry.remove();
        return nullRef();
      });
      assertThat(result).isNull();
      assertThat(fixture.jcacheLoading().containsKey(KEY_1)).isFalse();
      verify(writer).delete(KEY_1);
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void removeAll() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().putAll(ENTRIES);

      var entries = toCacheEntries(ENTRIES);
      verify(writer).writeAll(entries);

      fixture.jcache().removeAll(ENTRIES.keySet());
      verify(writer).deleteAll(ENTRIES.keySet());
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void removeAll_empty() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().putAll(ENTRIES);

      var entries = toCacheEntries(ENTRIES);
      verify(writer).writeAll(entries);

      fixture.jcache().removeAll(Set.of());
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void removeAll_fails() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      doThrow(CacheWriterException.class).when(writer).deleteAll(any());
      var keys = new HashSet<Integer>();
      keys.add(KEY_1);

      assertThrows(CacheWriterException.class, () -> fixture.jcache().removeAll(keys));
    }
  }

  @Test
  void removeAll_racingInsert() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer);
         var cache = fixture.jcache()) {
      cache.put(KEY_1, VALUE_1);

      // deleteAll runs between the key snapshot and the removal loop; a key inserted there must
      // survive removeAll() and stay out of the writer's delete set (else the store retains it and
      // a later read-through resurrects it)
      doAnswer(invocation -> {
        cache.put(KEY_2, VALUE_2);
        return null;
      }).when(writer).deleteAll(any());

      cache.removeAll();

      assertThat(cache.containsKey(KEY_1)).isFalse();
      assertThat(cache.containsKey(KEY_2)).isTrue();
      verify(writer).deleteAll(Set.of(KEY_1));
    }
  }

  /**
   * Per the integration contract, a {@link CacheWriter} failure must abort the operation without
   * mutating the cache or recording a statistic, so every update path must agree that no put is
   * counted. The {@code invoke} path historically recorded the put before calling the writer,
   * leaving a phantom {@code CachePuts} increment on failure.
   */
  @ParameterizedTest
  @MethodSource("updateOps")
  void writeOp_failingWriter_noPutsRecorded(Consumer<Cache<Integer, Integer>> op) {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = statisticsFixture(writer);
         var cache = fixture.jcache()) {
      cache.put(KEY_1, VALUE_1);
      long puts = getStatistics(cache).getCachePuts();

      doThrow(CacheWriterException.class).when(writer).write(any());
      doThrow(CacheWriterException.class).when(writer).writeAll(any());
      assertThrows(CacheException.class, () -> op.accept(cache));

      assertThat(getStatistics(cache).getCachePuts()).isEqualTo(puts);
      assertThat(cache.get(KEY_1)).isEqualTo(VALUE_1);
    }
  }

  /** A successful write-through update must still count the put after notifying the writer. */
  @ParameterizedTest
  @MethodSource("updateOps")
  void writeOp_writerSucceeds_recordsPut(Consumer<Cache<Integer, Integer>> op) {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = statisticsFixture(writer);
         var cache = fixture.jcache()) {
      cache.put(KEY_1, VALUE_1);
      long puts = getStatistics(cache).getCachePuts();

      op.accept(cache);

      assertThat(getStatistics(cache).getCachePuts()).isEqualTo(puts + 1);
      assertThat(cache.get(KEY_1)).isEqualTo(VALUE_2);
    }
  }

  static Stream<Arguments> updateOps() {
    return Stream.of(
        arguments(named("put", (Consumer<Cache<Integer, Integer>>) c -> c.put(KEY_1, VALUE_2))),
        arguments(named("putAll",
            (Consumer<Cache<Integer, Integer>>) c -> c.putAll(Map.of(KEY_1, VALUE_2)))),
        arguments(named("getAndPut",
            (Consumer<Cache<Integer, Integer>>) c -> c.getAndPut(KEY_1, VALUE_2))),
        arguments(named("replace",
            (Consumer<Cache<Integer, Integer>>) c -> c.replace(KEY_1, VALUE_2))),
        arguments(named("replaceConditionally",
            (Consumer<Cache<Integer, Integer>>) c -> c.replace(KEY_1, VALUE_1, VALUE_2))),
        arguments(named("getAndReplace",
            (Consumer<Cache<Integer, Integer>>) c -> c.getAndReplace(KEY_1, VALUE_2))),
        arguments(named("invoke", (Consumer<Cache<Integer, Integer>>) c ->
            c.invoke(KEY_1, (entry, args) -> {
              entry.setValue(VALUE_2);
              return nullRef();
            }))));
  }

  /**
   * Per the integration contract, a {@link CacheWriter} failure must abort the operation without
   * mutating the cache or recording a statistic, so every removal path must agree that no removal
   * is counted.
   */
  @ParameterizedTest
  @MethodSource("removalOps")
  void removeOp_failingWriter_noRemovalsRecorded(Consumer<Cache<Integer, Integer>> op) {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = statisticsFixture(writer);
         var cache = fixture.jcache()) {
      cache.put(KEY_1, VALUE_1);
      long removals = getStatistics(cache).getCacheRemovals();

      doThrow(CacheWriterException.class).when(writer).delete(any());
      doThrow(CacheWriterException.class).when(writer).deleteAll(any());
      assertThrows(CacheException.class, () -> op.accept(cache));

      assertThat(getStatistics(cache).getCacheRemovals()).isEqualTo(removals);
      assertThat(cache.get(KEY_1)).isEqualTo(VALUE_1);
    }
  }

  static Stream<Arguments> removalOps() {
    return Stream.of(
        arguments(named("remove", (Consumer<Cache<Integer, Integer>>) c -> c.remove(KEY_1))),
        arguments(named("removeConditionally",
            (Consumer<Cache<Integer, Integer>>) c -> c.remove(KEY_1, VALUE_1))),
        arguments(named("getAndRemove",
            (Consumer<Cache<Integer, Integer>>) c -> c.getAndRemove(KEY_1))),
        arguments(named("removeAll",
            (Consumer<Cache<Integer, Integer>>) c -> c.removeAll(Set.of(KEY_1)))),
        arguments(named("invoke", (Consumer<Cache<Integer, Integer>>) c ->
            c.invoke(KEY_1, (entry, args) -> {
              entry.remove();
              return nullRef();
            }))),
        arguments(named("iteratorRemove", (Consumer<Cache<Integer, Integer>>) c -> {
          var iterator = c.iterator();
          iterator.next();
          iterator.remove();
        })));
  }

  @Test
  void clear() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().putAll(ENTRIES);

      var entries = toCacheEntries(ENTRIES);
      verify(writer).writeAll(entries);

      fixture.jcache().clear();
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void close_fails() throws IOException {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      doThrow(IOException.class).when(writer).close();
      fixture.jcache().close();

      verify(writer, atLeastOnce()).close();
    }
  }

  @Test
  void hasCacheWriter() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      var noWriter = new CaffeineConfiguration<>(fixture.jcacheConfiguration())
          .setCacheWriterFactory(null);
      assertThat(noWriter.hasCacheWriter()).isFalse();
      assertThat(fixture.jcacheConfiguration().hasCacheWriter()).isTrue();
    }
  }

  @Test
  void disabled() {
    DisabledCacheWriter.get().write(null);
    DisabledCacheWriter.get().writeAll(null);
    DisabledCacheWriter.get().delete(null);
    DisabledCacheWriter.get().deleteAll(null);
  }

  private static ImmutableList<Cache.Entry<? extends Integer, ? extends Integer>> toCacheEntries(
      Map<Integer, Integer> map) {
    return map.entrySet().stream()
        .map(entry -> new EntryProxy<>(entry.getKey(), entry.getValue()))
        .collect(toImmutableList());
  }

  private interface CloseableCacheWriter extends CacheWriter<Integer, Integer>, Closeable {}
}
