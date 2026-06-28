/*
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
package com.github.benmanes.caffeine.jcache;

import static com.github.benmanes.caffeine.jcache.JCacheFixture.ENTRIES;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.EXPIRY_DURATION;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.KEYS;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.KEY_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.KEY_2;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.KEY_3;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.START_TIME;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_2;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_3;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.await;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.getExpirable;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.nullRef;
import static com.google.common.truth.Truth.assertThat;
import static java.lang.Thread.State.BLOCKED;
import static java.lang.Thread.State.TERMINATED;
import static java.lang.Thread.State.TIMED_WAITING;
import static java.lang.Thread.State.WAITING;
import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;
import static javax.cache.expiry.Duration.ETERNAL;
import static javax.cache.expiry.Duration.ONE_DAY;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.Closeable;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.configuration.Configuration;
import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.event.CacheEntryCreatedListener;
import javax.cache.event.CacheEntryEvent;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.event.CacheEntryListener;
import javax.cache.expiry.AccessedExpiryPolicy;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheLoaderException;
import javax.cache.integration.CacheWriter;
import javax.cache.integration.CompletionListener;
import javax.cache.integration.CompletionListenerFuture;

import org.apache.commons.lang3.mutable.MutableInt;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.processor.Action;
import com.github.benmanes.caffeine.jcache.processor.EntryProcessorEntry;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.UncheckedTimeoutException;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author github.com/kdombeck (Ken Dombeck)
 */
@SuppressFBWarnings("SIC_INNER_SHOULD_BE_STATIC_ANON")
final class CacheProxyTest {

  private static JCacheFixture jcacheFixture(CloseableCacheLoader loader,
      CloseableCacheWriter writer, CloseableExpiryPolicy expiry) {
    when(expiry.getExpiryForCreation()).thenReturn(ETERNAL);
    return JCacheFixture.builder()
        .configure(config -> {
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.setExpiryPolicyFactory(() -> expiry);
          config.setCacheLoaderFactory(() -> loader);
          config.setCacheWriterFactory(() -> writer);
          config.setStatisticsEnabled(true);
          config.setWriteThrough(false);
          config.setReadThrough(false);
        }).loading(config -> {
          config.setReadThrough(true);
        }).build();
  }

  @Test
  @SuppressFBWarnings("SPP_NON_USEFUL_TOSTRING")
  @SuppressWarnings({ "ObjectToString", "unchecked" })
  void getConfiguration_immutable() throws IOException {
    try (CloseableCacheEntryListener listener = Mockito.mock();
        var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      var config = fixture.jcache().getConfiguration(CaffeineConfiguration.class);
      var type = UnsupportedOperationException.class;

      assertThrows(type, () -> config.getCacheEntryListenerConfigurations().iterator().remove());
      assertThrows(type, () -> config.addCacheEntryListenerConfiguration(nullRef()));
      assertThrows(type, () -> config.setCacheLoaderFactory(nullRef()));
      assertThrows(type, () -> config.setCacheWriterFactory(nullRef()));
      assertThrows(type, () -> config.setCopierFactory(nullRef()));
      assertThrows(type, () -> config.setExecutorFactory(nullRef()));
      assertThrows(type, () -> config.setExpireAfterAccess(OptionalLong.empty()));
      assertThrows(type, () -> config.setExpireAfterWrite(OptionalLong.empty()));
      assertThrows(type, () -> config.setExpiryFactory(Optional.empty()));
      assertThrows(type, () -> config.setExpiryPolicyFactory(nullRef()));
      assertThrows(type, () -> config.setManagementEnabled(false));
      assertThrows(type, () -> config.setMaximumSize(OptionalLong.empty()));
      assertThrows(type, () -> config.setMaximumWeight(OptionalLong.empty()));
      assertThrows(type, () -> config.setNativeStatisticsEnabled(false));
      assertThrows(type, () -> config.setReadThrough(false));
      assertThrows(type, () -> config.setRefreshAfterWrite(OptionalLong.empty()));
      assertThrows(type, () -> config.setSchedulerFactory(nullRef()));
      assertThrows(type, () -> config.setStatisticsEnabled(false));
      assertThrows(type, () -> config.setStoreByValue(false));
      assertThrows(type, () -> config.setTickerFactory(nullRef()));
      assertThrows(type, () -> config.setTypes(String.class, String.class));
      assertThrows(type, () -> config.setWeigherFactory(Optional.empty()));
      assertThrows(type, () -> config.setWriteThrough(false));

      assertThat(config).isEqualTo(fixture.jcacheConfiguration());
      assertThat(config.toString()).isEqualTo(fixture.jcacheConfiguration().toString());

      var configuration = new MutableCacheEntryListenerConfiguration<>(
          /* listenerFactory= */ () -> listener, /* filterFactory= */ () -> event -> true,
          /* isOldValueRequired= */ false, /* isSynchronous= */ false);
      fixture.jcache().registerCacheEntryListener(configuration);
      assertThat(config.getCacheEntryListenerConfigurations()).hasSize(0);

      var config2 = fixture.jcache().getConfiguration(CaffeineConfiguration.class);
      assertThat(config2).isNotEqualTo(config);
      assertThat(config2.getCacheEntryListenerConfigurations()
          .spliterator().estimateSize()).isEqualTo(1);
      assertThat(config2.getCacheEntryListenerConfigurations()).hasSize(1);
      assertThat(config2.getCacheEntryListenerConfigurations().toString())
          .isEqualTo(List.of(configuration).toString());
    }
  }

  @Test
  void loadAll() throws IOException, InterruptedException, ExecutionException {
    try (CloseableCacheLoader loader = Mockito.mock();
        var fixture = jcacheFixture(loader, Mockito.mock(), Mockito.mock())) {
      fixture.jcache().put(KEY_1, VALUE_1);
      when(loader.loadAll(anyIterable()))
          .thenReturn(ImmutableMap.of(KEY_2, VALUE_2, KEY_3, VALUE_3));

      var completionListener = new CompletionListenerFuture();
      fixture.jcache().loadAll(KEYS, /* replaceExistingValues= */ false, completionListener);
      completionListener.get();
      assertThat(fixture.jcache()).containsExactlyElementsIn(ENTRIES.entrySet());
    }
  }

  @Test
  void loadAll_empty() throws InterruptedException, ExecutionException {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      var completionListener = new CompletionListenerFuture();
      fixture.jcache().loadAll(KEYS, /* replaceExistingValues= */ false, completionListener);
      completionListener.get();
      assertThat(fixture.jcache()).isEmpty();
    }
  }

  @Test
  @SuppressWarnings("NullableProblems")
  void loadAll_nullMapping() throws IOException, InterruptedException, ExecutionException {
    try (CloseableCacheLoader loader = Mockito.mock();
        var fixture = jcacheFixture(loader, Mockito.mock(), Mockito.mock())) {
      var result = new HashMap<@Nullable Integer, @Nullable Integer>();
      result.put(null, VALUE_1);
      result.put(KEY_1, null);

      var completionListener = new CompletionListenerFuture();
      when(loader.loadAll(anyIterable())).thenReturn(result);
      fixture.jcache().loadAll(KEYS, /* replaceExistingValues= */ false, completionListener);
      completionListener.get();
      assertThat(fixture.jcache()).isEmpty();
    }
  }

  @Test
  @SuppressWarnings("NullableProblems")
  void loadAll_replaceExisting_nullMapping()
      throws IOException, InterruptedException, ExecutionException {
    try (CloseableCacheLoader loader = Mockito.mock();
        var fixture = jcacheFixture(loader, Mockito.mock(), Mockito.mock())) {
      var result = new HashMap<@Nullable Integer, @Nullable Integer>();
      result.put(KEY_1, VALUE_1);
      result.put(KEY_2, null);
      result.put(null, VALUE_3);

      var completionListener = new CompletionListenerFuture();
      when(loader.loadAll(anyIterable())).thenReturn(result);
      fixture.jcache().loadAll(KEYS, /* replaceExistingValues= */ true, completionListener);
      completionListener.get();
      assertThat(fixture.jcache()).containsExactlyElementsIn(Map.of(KEY_1, VALUE_1).entrySet());
    }
  }

  @Test
  @SuppressWarnings("NullableProblems")
  void loadAll_loading_replaceExisting_nullMapping()
      throws IOException, InterruptedException, ExecutionException {
    try (CloseableCacheLoader loader = Mockito.mock();
        var fixture = jcacheFixture(loader, Mockito.mock(), Mockito.mock())) {
      var result = new HashMap<@Nullable Integer, @Nullable Integer>();
      result.put(KEY_1, VALUE_1);
      result.put(KEY_2, null);
      result.put(null, VALUE_3);

      var completionListener = new CompletionListenerFuture();
      when(loader.loadAll(anyIterable())).thenReturn(result);
      fixture.jcacheLoading().loadAll(KEYS,
          /* replaceExistingValues= */ true, completionListener);
      completionListener.get();
      assertThat(fixture.jcacheLoading())
          .containsExactlyElementsIn(Map.of(KEY_1, VALUE_1).entrySet());
    }
  }

  @Test
  void loadAll_doesNotRecordHitsOrMisses() throws Exception {
    // Per JSR-107 1.1.1 §12.4 (p.126): loadAll's stats columns Puts/Removals/
    // Hits/Misses are all No — the operation must not touch these counters
    // even for the keep-existing path that internally examines present entries.
    try (var fixture = JCacheFixture.builder()
        .configure(config -> config.setExecutorFactory(MoreExecutors::directExecutor))
        .build()) {
      fixture.jcacheLoading().put(KEY_1, VALUE_1);
      fixture.cacheManager().enableStatistics(
          fixture.jcacheLoading().getName(), /* enabled = */ true);

      var stats = JCacheFixture.getStatistics(fixture.jcacheLoading());
      long hitsBefore = stats.getCacheHits();
      long missesBefore = stats.getCacheMisses();
      long putsBefore = stats.getCachePuts();

      var completionListener = new CompletionListenerFuture();
      fixture.jcacheLoading().loadAll(KEYS,
          /* replaceExistingValues= */ false, completionListener);
      completionListener.get();

      assertThat(stats.getCacheHits()).isEqualTo(hitsBefore);
      assertThat(stats.getCacheMisses()).isEqualTo(missesBefore);
      assertThat(stats.getCachePuts()).isEqualTo(putsBefore);
    }
  }

  @Test
  void iterator_recordsHitPerYield() {
    // Per JSR-107 1.1.1 §12.4 statistics table (p.126): iterator()'s "Hits"
    // column is Yes — every yielded entry counts as a hit.
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.jcache().put(KEY_2, VALUE_2);
      fixture.jcache().put(KEY_3, VALUE_3);

      var stats = JCacheFixture.getStatistics(fixture.jcache());
      long hitsBefore = stats.getCacheHits();
      int size = Iterators.size(fixture.jcache().iterator());
      assertThat(size).isEqualTo(3);
      assertThat(stats.getCacheHits() - hitsBefore).isEqualTo(3);
    }
  }

  @Test
  void get_expired_statsDisabled_doesNotRecordGetTime() {
    // Covers the statsEnabled=false branch on the expired-entry early return.
    // The auto-increment pattern keeps the entry visible to cache.getIfPresent
    // (so JCache reaches its expiry check) while the subsequent ticker read
    // inside the get() flow pushes millis past the entry's expireTimeMillis.
    ExpiryPolicy expiry = Mockito.mock();
    when(expiry.getExpiryForCreation()).thenReturn(Duration.ONE_MINUTE);
    try (var fixture = JCacheFixture.builder()
        .configure(config -> config.setExpiryPolicyFactory(() -> expiry))
        .build()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.ticker().setAutoIncrementStep(EXPIRY_DURATION.dividedBy(2));
      assertThat(fixture.jcache().get(KEY_1)).isNull();
      assertThat(getExpirable(fixture.jcache(), KEY_1)).isNull();
    }
  }

  @Test
  void loadAll_cacheLoaderException_unwrapped() {
    // Covers the inner catch (CacheLoaderException) branch in
    // CacheProxy.loadAll's runAsync — the wrapped path is exercised by
    // loadAll_executorRejects_notifiesListener.
    var cle = new CacheLoaderException("test");
    CacheLoader<Integer, Integer> loader = Mockito.mock();
    when(loader.loadAll(anyIterable())).thenThrow(cle);
    try (var fixture = JCacheFixture.builder()
        .configure(config -> {
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.setCacheLoaderFactory(() -> loader);
        }).build()) {
      assertThat(fixture.jcache()).isNotInstanceOf(LoadingCacheProxy.class);
      CompletionListener listener = Mockito.mock();
      fixture.jcache().loadAll(KEYS, /* replaceExistingValues= */ true, listener);
      verify(listener).onException(cle);
    }
  }

  @Test
  void loadAll_loading_cacheLoaderException_unwrapped() {
    // Covers the inner catch (CacheLoaderException) branch in
    // LoadingCacheProxy.loadAll's runAsync.
    var cle = new CacheLoaderException("test");
    CacheLoader<Integer, Integer> loader = Mockito.mock();
    when(loader.loadAll(anyIterable())).thenThrow(cle);
    try (var fixture = JCacheFixture.builder()
        .configure(config -> config.setExecutorFactory(MoreExecutors::directExecutor))
        .loading(config -> {
          config.setCacheLoaderFactory(() -> loader);
          config.setReadThrough(true);
        }).build()) {
      CompletionListener listener = Mockito.mock();
      fixture.jcacheLoading().loadAll(KEYS, /* replaceExistingValues= */ true, listener);
      verify(listener).onException(cle);
    }
  }

  @Test
  void iterator_remove_calledTwice_throwsISE() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      fixture.jcache().put(KEY_1, VALUE_1);
      var iter = fixture.jcache().iterator();
      iter.next();
      iter.remove();
      assertThrows(IllegalStateException.class, iter::remove);
    }
  }

  @Test
  void iterator_remove_valueChangedConcurrently_doesNotRemove() {
    // Covers the value-mismatch branch in EntryIterator.remove's lambda:
    // value changed between next() and remove(), so the conditional remove
    // returns false and `removed[0]` stays false.
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      fixture.jcache().put(KEY_1, VALUE_1);
      var iter = fixture.jcache().iterator();
      iter.next();
      fixture.jcache().put(KEY_1, VALUE_2);
      iter.remove();
      assertThat(fixture.jcache().get(KEY_1)).isEqualTo(VALUE_2);
    }
  }

  @Test
  void loadAll_listenerOnCompletionThrows_doesNotFireOnException() {
    // Per JSR-107 1.1.1 p.64: onCompletion and onException are the terminal
    // success/failure callbacks for one operation. If the user listener's
    // onCompletion throws, it must not also route through onException.
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      CompletionListener listener = Mockito.mock();
      doThrow(new RuntimeException("boom")).when(listener).onCompletion();
      fixture.jcache().loadAll(KEYS, /* replaceExistingValues= */ true, listener);
      verify(listener).onCompletion();
      verify(listener, Mockito.never()).onException(any());
    }
  }

  @Test
  void loadAll_executorRejects_notifiesListener() {
    Executor rejecting = task -> { throw new RejectedExecutionException("test"); };
    CacheLoader<Integer, Integer> loader = Mockito.mock();
    try (var fixture = JCacheFixture.builder()
        .configure(config -> {
          config.setExecutorFactory(() -> rejecting);
          config.setCacheLoaderFactory(() -> loader);
        }).build()) {
      // jcache() is a plain CacheProxy (no read-through); CacheProxy.loadAll wraps
      // the rejection in CacheLoaderException to match its inner-catch convention.
      assertThat(fixture.jcache()).isNotInstanceOf(LoadingCacheProxy.class);
      CompletionListener listener = Mockito.mock();
      fixture.jcache().loadAll(KEYS, /* replaceExistingValues= */ true, listener);
      verify(listener).onException(any(CacheLoaderException.class));
    }
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void get_miss_recordsGetTime() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      fixture.ticker().setAutoIncrementStep(1, TimeUnit.SECONDS);
      assertThat(fixture.jcache().get(KEY_1)).isNull();

      var stats = JCacheFixture.getStatistics(fixture.jcache());
      assertThat(stats.getCacheMisses()).isEqualTo(1);
      assertThat(stats.getCacheGets()).isEqualTo(1);
      assertThat(stats.getAverageGetTime()).isGreaterThan(0F);
    }
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void get_expired_recordsGetTime() {
    try (var fixture = JCacheFixture.builder()
        .configure(config -> {
          config.setExpiryPolicyFactory(() -> new AccessedExpiryPolicy(
              new Duration(TimeUnit.MILLISECONDS, EXPIRY_DURATION.toMillis())));
          config.setStatisticsEnabled(true);
        }).build()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.ticker().setAutoIncrementStep(EXPIRY_DURATION.dividedBy(2));
      assertThat(fixture.jcache().get(KEY_1)).isNull();

      var stats = JCacheFixture.getStatistics(fixture.jcache());
      assertThat(stats.getCacheMisses()).isEqualTo(1);
      assertThat(stats.getAverageGetTime()).isGreaterThan(0F);
    }
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void replace_miss_recordsGetTime() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      fixture.ticker().setAutoIncrementStep(1, TimeUnit.SECONDS);
      assertThat(fixture.jcache().replace(KEY_1, VALUE_1)).isFalse();

      var stats = JCacheFixture.getStatistics(fixture.jcache());
      assertThat(stats.getCacheMisses()).isEqualTo(1);
      assertThat(stats.getAverageGetTime()).isGreaterThan(0F);
    }
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void replace_hit_recordsGetTime() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.ticker().setAutoIncrementStep(1, TimeUnit.SECONDS);
      assertThat(fixture.jcache().replace(KEY_1, VALUE_2)).isTrue();

      var stats = JCacheFixture.getStatistics(fixture.jcache());
      assertThat(stats.getCacheHits()).isEqualTo(1);
      assertThat(stats.getAverageGetTime()).isGreaterThan(0F);
      assertThat(stats.getAveragePutTime()).isGreaterThan(0F);
    }
  }

  @Test
  void loadAll_loading_executorRejects_notifiesListener() {
    Executor rejecting = task -> { throw new RejectedExecutionException("test"); };
    try (var fixture = JCacheFixture.builder()
        .configure(config -> config.setExecutorFactory(() -> rejecting))
        .build()) {
      CompletionListener listener = Mockito.mock();
      fixture.jcacheLoading().loadAll(KEYS, /* replaceExistingValues= */ true, listener);
      verify(listener).onException(any(CacheLoaderException.class));
    }
  }

  @ParameterizedTest @MethodSource("exceptions")
  void get_exception(Exception underlying,
      Class<Exception> thrown, boolean wrapped) throws IOException {
    LoadingCache<Object, @Nullable Expirable<Object>> cache = Mockito.mock();
    when(cache.getIfPresent(any())).thenThrow(underlying);
    try (CloseableExpiryPolicy expiry = Mockito.mock();
        var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), expiry);
        var proxy = new LoadingCacheProxy<>("dummy", Runnable::run, fixture.cacheManager(),
            Mockito.mock(), cache, Mockito.mock(), Mockito.mock(),
            expiry, Mockito.mock(), Mockito.mock())) {
      var actual = assertThrows(thrown, () -> proxy.get(KEY_1));
      if (wrapped) {
        assertThat(actual).hasCauseThat().isSameInstanceAs(underlying);
      } else {
        assertThat(actual).isSameInstanceAs(underlying);
      }
    }
  }

  @ParameterizedTest @MethodSource("exceptions")
  void getAll_exception(Exception underlying,
      Class<Exception> thrown, boolean wrapped) throws IOException {
    LoadingCache<Object, @Nullable Expirable<Object>> cache = Mockito.mock();
    when(cache.getAllPresent(any())).thenThrow(underlying);
    try (CloseableExpiryPolicy expiry = Mockito.mock();
        var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), expiry);
        var proxy = new LoadingCacheProxy<>("dummy", Runnable::run, fixture.cacheManager(),
            Mockito.mock(), cache, Mockito.mock(), Mockito.mock(),
            expiry, Mockito.mock(), Mockito.mock())) {
      var actual = assertThrows(thrown, () -> proxy.getAll(Set.of(KEY_1)));
      if (wrapped) {
        assertThat(actual).hasCauseThat().isSameInstanceAs(underlying);
      } else {
        assertThat(actual).isSameInstanceAs(underlying);
      }
    }
  }

  @Test
  void get_unexpired() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      checkReadWhenUnexpired(fixture.jcache(),
          fixture.currentTime().plus(EXPIRY_DURATION).toMillis(),
          key -> assertThat(fixture.jcache().get(key)).isNull());
      assertThat(fixture.jcache().statistics.getCacheMisses()).isEqualTo(1);
    }
  }

  @Test
  void getLoading_unexpired() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      checkReadWhenUnexpired(fixture.jcacheLoading(),
          fixture.currentTime().plus(EXPIRY_DURATION).toMillis(),
          key -> assertThat(fixture.jcacheLoading().get(key)).isNotNull());
      assertThat(fixture.jcacheLoading().statistics.getCacheMisses()).isEqualTo(1);
    }
  }

  @Test
  void getAll_unexpired() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      checkReadWhenUnexpired(fixture.jcache(),
          fixture.currentTime().plus(EXPIRY_DURATION).toMillis(),
          key -> assertThat(fixture.jcache().getAll(Set.of(key))).isEqualTo(Map.of()));
      assertThat(fixture.jcache().statistics.getCacheMisses()).isEqualTo(1);
    }
  }

  @Test
  void getAll_partial() throws IOException {
    try (CloseableCacheLoader loader = Mockito.mock();
        var fixture = jcacheFixture(loader, Mockito.mock(), Mockito.mock())) {
      fixture.jcacheLoading().put(KEY_1, VALUE_1);
      fixture.jcacheLoading().put(KEY_2, VALUE_2);
      when(loader.loadAll(any())).thenReturn(Map.of(KEY_3, VALUE_3));
      var result = fixture.jcacheLoading().getAll(ENTRIES.keySet());
      assertThat(result).containsExactlyEntriesIn(ENTRIES);
    }
  }

  @Test
  void containsKey_eternal() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      fixture.jcache().put(KEY_1, VALUE_1);
      var expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.isEternal()).isTrue();
      assertThat(fixture.jcache().containsKey(KEY_1)).isTrue();
      assertThat(expirable.toString()).isEqualTo(String.format(
          US, "Expirable{value=%s, expireTimeMillis=%,d}", VALUE_1, Long.MAX_VALUE));
    }
  }

  @Test
  void containsKey_notExpired() throws IOException {
    try (CloseableExpiryPolicy expiry = Mockito.mock();
        var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), expiry)) {
      when(expiry.getExpiryForCreation()).thenReturn(Duration.FIVE_MINUTES);
      fixture.jcache().put(KEY_1, VALUE_1);

      var expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.isEternal()).isFalse();
      assertThat(fixture.jcache().containsKey(KEY_1)).isTrue();
      assertThat(expirable.toString()).isEqualTo(String.format(
          US, "Expirable{value=%s, expireTimeMillis=%,d}", VALUE_1,
          START_TIME.toMillis() + TimeUnit.MINUTES.toMillis(5)));
    }
  }

  @Test
  void containsKey_unexpired() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      checkReadWhenUnexpired(fixture.jcache(),
          fixture.currentTime().plus(EXPIRY_DURATION).toMillis(),
          key -> assertThat(fixture.jcache().containsKey(key)).isFalse());
      assertThat(fixture.jcache().statistics.getCacheMisses()).isEqualTo(0);
    }
  }

  private static void checkReadWhenUnexpired(
      CacheProxy<Integer, Integer> jcache, long expireTimeMillis, Consumer<Integer> read) {
    var done = new AtomicBoolean();
    var started = new AtomicBoolean();
    Expirable<Integer> expirable = Mockito.mock();
    when(expirable.get()).thenReturn(VALUE_1);
    when(expirable.hasExpired(anyLong())).thenReturn(true);
    when(expirable.getExpireTimeMillis()).thenReturn(expireTimeMillis);
    jcache.cache.asMap().put(KEY_1, expirable);
    jcache.cache.asMap().compute(KEY_1, (key, v) -> {
      var reader = new Thread(() -> {
        started.set(true);
        read.accept(key);
        done.set(true);
      });
      reader.start();
      await().untilTrue(started);
      var threadState = EnumSet.of(BLOCKED, WAITING);
      await().until(() -> threadState.contains(reader.getState()));
      return new Expirable<>(VALUE_2, expireTimeMillis);
    });
    await().untilTrue(done);
    assertThat(jcache.statistics.getCacheEvictions()).isEqualTo(0);
  }

  @Test
  void putAll_empty() throws IOException {
    try (CloseableCacheWriter writer = Mockito.mock();
        var fixture = jcacheFixture(Mockito.mock(), writer, Mockito.mock())) {
      fixture.jcache().putAll(Map.of());
      verifyNoInteractions(writer);
    }
  }

  @Test
  void remove_eternal() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      fixture.jcache().put(KEY_1, VALUE_1);
      var expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.isEternal()).isTrue();
      assertThat(fixture.jcache().remove(KEY_1)).isTrue();
    }
  }

  @Test
  void remove_notExpired() throws IOException {
    try (CloseableExpiryPolicy expiry = Mockito.mock();
        var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), expiry)) {
      when(expiry.getExpiryForCreation()).thenReturn(Duration.FIVE_MINUTES);
      fixture.jcache().put(KEY_1, VALUE_1);

      var expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.isEternal()).isFalse();
      assertThat(fixture.jcache().remove(KEY_1)).isTrue();
    }
  }

  @Test @Tag("isolated")
  @SuppressWarnings({"CheckReturnValue", "EnumOrdinal"})
  void postProcess_unknownAction() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock());
        var actionTypes = Mockito.mockStatic(Action.class)) {
      var unknown = Mockito.mock(Action.class);
      when(unknown.ordinal()).thenReturn(6);
      actionTypes.when(Action::values).thenReturn(new Action[] {
          Action.NONE, Action.READ, Action.CREATED, Action.UPDATED,
          Action.LOADED, Action.DELETED, unknown });
      EntryProcessorEntry<Integer, Integer> entry = Mockito.mock();
      when(entry.getAction()).thenReturn(unknown);
      assertThrows(IllegalStateException.class, () -> fixture.jcache().postProcess(null, entry, 0));
    }
  }

  @Test
  void postProcess_none() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      var expirable = new Expirable<>(VALUE_1,
          fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
      EntryProcessorEntry<Integer, Integer> entry = Mockito.mock();
      when(entry.getAction()).thenReturn(Action.NONE);
      assertThat(fixture.jcache().postProcess(expirable, entry, 0L)).isSameInstanceAs(expirable);
    }
  }

  @Test
  void postProcess_deleted_present() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      var expirable = new Expirable<>(VALUE_1,
          fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
      EntryProcessorEntry<Integer, Integer> entry = Mockito.mock();
      when(entry.getAction()).thenReturn(Action.DELETED);
      when(entry.getKey()).thenReturn(KEY_1);

      var stats = JCacheFixture.getStatistics(fixture.jcache());
      long removalsBefore = stats.getCacheRemovals();
      assertThat(fixture.jcache().postProcess(expirable, entry, 0L)).isNull();
      assertThat(stats.getCacheRemovals()).isEqualTo(removalsBefore + 1);
    }
  }

  @Test
  void postProcess_deleted_missing() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      EntryProcessorEntry<Integer, Integer> entry = Mockito.mock();
      when(entry.getAction()).thenReturn(Action.DELETED);
      when(entry.getKey()).thenReturn(KEY_1);

      var stats = JCacheFixture.getStatistics(fixture.jcache());
      long removalsBefore = stats.getCacheRemovals();
      assertThat(fixture.jcache().postProcess(null, entry, 0L)).isNull();
      assertThat(stats.getCacheRemovals()).isEqualTo(removalsBefore);
    }
  }

  @Test
  void postProcess_deleted_expired_publishesExpiredNotRemoved() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      var expired = new Expirable<>(VALUE_1, fixture.currentTime().toMillis() - 1);
      EntryProcessorEntry<Integer, Integer> entry = Mockito.mock();
      when(entry.getAction()).thenReturn(Action.DELETED);
      when(entry.getKey()).thenReturn(KEY_1);

      var stats = JCacheFixture.getStatistics(fixture.jcache());
      long removalsBefore = stats.getCacheRemovals();
      long evictionsBefore = stats.getCacheEvictions();
      assertThat(fixture.jcache().postProcess(expired, entry, 0L)).isNull();
      assertThat(stats.getCacheRemovals()).isEqualTo(removalsBefore);
      assertThat(stats.getCacheEvictions()).isEqualTo(evictionsBefore + 1);
    }
  }

  @Test
  void postProcess_created_expiredPrior_publishesExpiredForPrior() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      var expired = new Expirable<>(VALUE_1, fixture.currentTime().toMillis() - 1);
      EntryProcessorEntry<Integer, Integer> entry = Mockito.mock();
      when(entry.getAction()).thenReturn(Action.CREATED);
      when(entry.getKey()).thenReturn(KEY_1);
      when(entry.getValue()).thenReturn(VALUE_2);

      var stats = JCacheFixture.getStatistics(fixture.jcache());
      long evictionsBefore = stats.getCacheEvictions();
      long putsBefore = stats.getCachePuts();
      var result = fixture.jcache().postProcess(expired, entry, 0L);
      assertThat(result).isNotNull();
      assertThat(result.get()).isEqualTo(VALUE_2);
      assertThat(stats.getCacheEvictions()).isEqualTo(evictionsBefore + 1);
      assertThat(stats.getCachePuts()).isEqualTo(putsBefore + 1);
    }
  }

  @Test
  void copyValue_null() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      assertThat(fixture.jcache().copyValue(null)).isNull();
    }
  }

  @Test
  void setAccessExpireTime_eternal() throws IOException {
    try (CloseableExpiryPolicy expiry = Mockito.mock();
        var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), expiry)) {
      when(expiry.getExpiryForCreation()).thenReturn(ONE_DAY);
      when(expiry.getExpiryForAccess()).thenReturn(ETERNAL);
      fixture.jcache().put(KEY_1, VALUE_1);

      var expirable = requireNonNull(getExpirable(fixture.jcache(), KEY_1));
      assertThat(expirable.isEternal()).isFalse();

      fixture.jcache().setAccessExpireTime(KEY_1, expirable, 0);
      assertThat(expirable.isEternal()).isTrue();

      var policy = fixture.jcache().cache.policy().expireVariably().orElseThrow();
      assertThat(policy.getExpiresAfter(KEY_1, TimeUnit.NANOSECONDS).orElseThrow())
          .isAtLeast(Long.MAX_VALUE >> 1);
    }
  }

  @Test
  void setAccessExpireTime_exception() throws IOException {
    try (CloseableExpiryPolicy expiry = Mockito.mock();
        var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), expiry)) {
      when(expiry.getExpiryForAccess()).thenThrow(IllegalStateException.class);
      var expirable = new Expirable<>(KEY_1, 0);
      fixture.jcache().setAccessExpireTime(KEY_1, expirable, 0);
      assertThat(expirable.getExpireTimeMillis()).isEqualTo(0);
    }
  }

  @Test
  void setAccessExpireTime_adjustedTimeSentinelZero() throws IOException {
    try (CloseableExpiryPolicy expiry = Mockito.mock();
        var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), expiry)) {
      fixture.jcache().put(KEY_1, VALUE_1);

      var duration = Mockito.spy(new Duration(TimeUnit.MILLISECONDS, 1));
      when(duration.getAdjustedTime(anyLong())).thenReturn(0L);
      when(expiry.getExpiryForAccess()).thenReturn(duration);

      var expirable = requireNonNull(getExpirable(fixture.jcache(), KEY_1));
      fixture.jcache().setAccessExpireTime(KEY_1, expirable, 1L);
      assertThat(expirable.getExpireTimeMillis()).isEqualTo(-1L);
    }
  }

  @Test
  void setAccessExpireTime_adjustedTimeSentinelMaxValue() throws IOException {
    try (CloseableExpiryPolicy expiry = Mockito.mock();
        var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), expiry)) {
      fixture.jcache().put(KEY_1, VALUE_1);

      var duration = Mockito.spy(new Duration(TimeUnit.MILLISECONDS, 1));
      when(duration.getAdjustedTime(anyLong())).thenReturn(Long.MAX_VALUE);
      when(expiry.getExpiryForAccess()).thenReturn(duration);

      var expirable = requireNonNull(getExpirable(fixture.jcache(), KEY_1));
      fixture.jcache().setAccessExpireTime(KEY_1, expirable, 1L);
      assertThat(expirable.getExpireTimeMillis()).isEqualTo(Long.MAX_VALUE - 1);
    }
  }

  @Test
  void getWriteExpireTime_exception_create() throws IOException {
    try (CloseableExpiryPolicy expiry = Mockito.mock();
        var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), expiry)) {
      when(expiry.getExpiryForCreation()).thenThrow(IllegalStateException.class);
      long time = fixture.jcache().getWriteExpireTimeMillis(true);
      assertThat(time).isEqualTo(Long.MAX_VALUE);
    }
  }

  @Test
  void getWriteExpireTime_exception_update() throws IOException {
    try (CloseableExpiryPolicy expiry = Mockito.mock();
        var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), expiry)) {
      when(expiry.getExpiryForUpdate()).thenThrow(IllegalStateException.class);
      long time = fixture.jcache().getWriteExpireTimeMillis(false);
      assertThat(time).isEqualTo(Long.MIN_VALUE);
    }
  }

  @Test
  void getWriteExpireTimeMillis_adjustedTimeSentinelZero() throws IOException {
    try (CloseableExpiryPolicy expiry = Mockito.mock();
        var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), expiry)) {
      var duration = Mockito.spy(new Duration(TimeUnit.MILLISECONDS, 1));
      when(duration.getAdjustedTime(anyLong())).thenReturn(0L);
      when(expiry.getExpiryForCreation()).thenReturn(duration);

      long time = fixture.jcache().getWriteExpireTimeMillis(true);
      assertThat(time).isEqualTo(-1L);
    }
  }

  @Test
  void getWriteExpireTimeMillis_adjustedTimeSentinelMaxValue() throws IOException {
    try (CloseableExpiryPolicy expiry = Mockito.mock();
        var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), expiry)) {
      var duration = Mockito.spy(new Duration(TimeUnit.MILLISECONDS, 1));
      when(duration.getAdjustedTime(anyLong())).thenReturn(Long.MAX_VALUE);
      when(expiry.getExpiryForCreation()).thenReturn(duration);

      long time = fixture.jcache().getWriteExpireTimeMillis(true);
      assertThat(time).isEqualTo(Long.MAX_VALUE - 1);
    }
  }

  @Test
  void unwrap_fail() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      assertThrows(IllegalArgumentException.class, () ->
          fixture.jcache().unwrap(CaffeineConfiguration.class));
    }
  }

  @Test
  void unwrap() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      assertThat(fixture.jcache().unwrap(Cache.class)).isSameInstanceAs(fixture.jcache());
      assertThat(fixture.jcache().unwrap(CacheProxy.class)).isSameInstanceAs(fixture.jcache());
      assertThat(fixture.jcache().unwrap(com.github.benmanes.caffeine.cache.Cache.class))
          .isSameInstanceAs(fixture.jcache().cache);
    }
  }

  @Test
  void unwrap_configuration() {
    abstract class Dummy implements Configuration<Integer, Integer> {
      private static final long serialVersionUID = 1L;
    }
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      assertThrows(IllegalArgumentException.class, () ->
          fixture.jcache().getConfiguration(Dummy.class));
    }
  }

  @Test
  void unwrap_entry() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      fixture.jcache().put(KEY_1, VALUE_1);
      var item = fixture.jcache().iterator().next();
      assertThrows(IllegalArgumentException.class, () -> item.unwrap(String.class));
    }
  }

  @Test
  void put_zeroExpiryOnCreate_publishesCopiedValue() {
    CacheEntryExpiredListener<Integer, MutableInt> listener = Mockito.mock();
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      var config = new MutableConfiguration<Integer, MutableInt>()
          .setExpiryPolicyFactory(CreatedExpiryPolicy.factoryOf(
              new Duration(TimeUnit.MILLISECONDS, 0L)))
          .addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
              /* listenerFactory= */ () -> listener, /* filterFactory= */ null,
              /* isOldValueRequired= */ true, /* isSynchronous= */ true));
      try (Cache<Integer, MutableInt> cache = fixture.cacheManager()
              .createCache("expire-on-create", config)) {
        var original = new MutableInt(42);
        cache.put(KEY_1, original);

        ArgumentCaptor<Iterable<CacheEntryEvent<? extends Integer, ? extends MutableInt>>> events =
            ArgumentCaptor.captor();
        verify(listener).onExpired(events.capture());
        var captured = events.getValue().iterator().next().getValue();
        assertThat(captured).isNotSameInstanceAs(original);
        assertThat(captured).isEqualTo(original);
      }
    }
  }

  @Test
  void replaceConditional_storesCopiedValue() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      var config = new MutableConfiguration<Integer, MutableInt>();
      try (Cache<Integer, MutableInt> cache = fixture.cacheManager()
              .createCache("replace-conditional", config)) {
        var original = new MutableInt(VALUE_1);
        var replacement = new MutableInt(VALUE_2);
        cache.put(KEY_1, original);
        assertThat(cache.replace(KEY_1, original, replacement)).isTrue();
        replacement.setValue(VALUE_3);
        assertThat(cache.get(KEY_1)).isEqualTo(new MutableInt(VALUE_2));
      }
    }
  }

  @Test
  void invoke_setValue_storesCopiedValue() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      var config = new MutableConfiguration<Integer, MutableInt>();
      try (Cache<Integer, MutableInt> cache = fixture.cacheManager()
              .createCache("invoke-set-value", config)) {
        var supplied = new MutableInt(VALUE_1);
        cache.invoke(KEY_1, (entry, args) -> {
          entry.setValue(supplied);
          return null;
        });
        supplied.setValue(VALUE_2);
        assertThat(cache.get(KEY_1)).isEqualTo(new MutableInt(VALUE_1));
      }
    }
  }

  @Test
  @SuppressFBWarnings("HES_LOCAL_EXECUTOR_SERVICE")
  void close_awaitsInFlightLoadAll() throws InterruptedException {
    @SuppressWarnings("PMD.CloseResource")
    var executor = Executors.newSingleThreadExecutor();
    var letProceed = new CountDownLatch(1);
    var listenerNotified = new AtomicBoolean();
    var blockingLoader = new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) {
        try {
          letProceed.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return key;
      }
      @Override public Map<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
        var result = new HashMap<Integer, Integer>();
        keys.forEach(key -> result.put(key, load(key)));
        return result;
      }
    };
    var listener = new CompletionListener() {
      @Override public void onCompletion() {
        listenerNotified.set(true);
      }
      @Override public void onException(Exception e) { /* unused */ }
    };
    try {
      try (var fixture = JCacheFixture.builder()
          .loading(config -> {
            config.setCacheLoaderFactory(() -> blockingLoader);
            config.setReadThrough(true);
          })
          .configure(config -> config.setExecutorFactory(() -> executor))
          .build()) {
        fixture.jcacheLoading().loadAll(Set.of(KEY_1), /* replaceExistingValues= */ true, listener);

        var closeThread = new Thread(() -> fixture.jcacheLoading().close());
        closeThread.start();

        // close() must wait for the in-flight loadAll future. Pre-fix the race window
        // between runAsync and inFlight.add could let close() observe an empty inFlight
        // and return before the loader completed; post-fix the registration is inside
        // the same configuration lock that close() takes.
        var blocked = EnumSet.of(BLOCKED, WAITING, TIMED_WAITING);
        await().until(() -> blocked.contains(closeThread.getState()));
        assertThat(closeThread.isAlive()).isTrue();

        letProceed.countDown();
        closeThread.join();
        assertThat(listenerNotified.get()).isTrue();
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @SuppressFBWarnings("HES_LOCAL_EXECUTOR_SERVICE")
  void getAll_awaitsSynchronousListeners() throws InterruptedException {
    @SuppressWarnings("PMD.CloseResource")
    var executor = Executors.newSingleThreadExecutor();
    try {
      var letProceed = new AtomicBoolean();
      var listenerEntered = new AtomicBoolean();
      CacheEntryCreatedListener<Integer, Integer> listener = events -> {
        listenerEntered.set(true);
        await().untilTrue(letProceed);
      };
      var listenerConfig = new MutableCacheEntryListenerConfiguration<>(
          /* listenerFactory= */ () -> listener, /* filterFactory= */ null,
          /* isOldValueRequired= */ false, /* isSynchronous= */ true);
      try (var fixture = JCacheFixture.builder()
          .configure(config -> {
            config.setExecutorFactory(() -> executor);
            config.addCacheEntryListenerConfiguration(listenerConfig);
          }).build()) {
        // Both publish and getAll must run on the same worker thread so they share the
        // dispatcher's per-thread `pending` ThreadLocal. publish queues the listener on
        // the executor (separate thread) where it parks, so the future stays incomplete;
        // getAll(emptySet) is then expected to block in awaitSynchronous waiting on it.
        var worker = new Thread(() -> {
          fixture.jcache().dispatcher.publishCreated(fixture.jcache(), KEY_1, VALUE_1);
          assertThat(fixture.jcache().getAll(Set.of())).isEmpty();
        });
        worker.start();
        await().untilTrue(listenerEntered);

        // Pre-fix: getAll returns immediately and the worker terminates without joining
        // the pending future. Post-fix: getAll blocks in awaitSynchronous.
        var settled = EnumSet.of(BLOCKED, WAITING, TERMINATED);
        await().until(() -> settled.contains(worker.getState()));
        assertThat(worker.isAlive()).isTrue();

        letProceed.set(true);
        worker.join();
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @SuppressWarnings("try")
  void invokeAll_emptyKeys_closed_throws() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock());
         var cache = fixture.jcache()) {
      cache.close();
      assertThrows(IllegalStateException.class, () ->
          cache.invokeAll(Set.of(), Mockito.mock()));
    }
  }

  @Test
  void invokeAll_emptyKeys_nullProcessor_throws() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      assertThrows(NullPointerException.class, () ->
          fixture.jcache().invokeAll(Set.of(), nullRef()));
    }
  }

  @Test
  void close_alreadyClosed() {
    LoadingCache<Integer, @Nullable Expirable<Integer>> underlying = Mockito.mock();
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      var cache = new CacheProxy<Integer, Integer>("mock", Mockito.mock(), fixture.cacheManager(),
          Mockito.mock(), underlying, Mockito.mock(), Mockito.mock(), Mockito.mock(),
          Mockito.mock(), Mockito.mock()) {
        int isClosed;
        @Override public boolean isClosed() {
          isClosed++;
          return (isClosed > 1);
        }
        boolean isReallyClosed() {
          return super.isClosed();
        }
      };
      try (cache) {
        cache.close();
        assertThat(cache.isClosed).isEqualTo(2);
        assertThat(cache.isReallyClosed()).isFalse();
      }
    }
  }

  @Test
  void close_fails() throws IOException {
    try (CloseableCacheLoader loader = Mockito.mock();
        CloseableCacheWriter writer = Mockito.mock();
        CloseableExpiryPolicy expiry = Mockito.mock();
        CloseableCacheEntryListener listener = Mockito.mock();
        var fixture = jcacheFixture(loader, writer, expiry)) {
      doThrow(IOException.class).when(loader).close();
      doThrow(IOException.class).when(writer).close();
      doThrow(IOException.class).when(expiry).close();
      doThrow(IOException.class).when(listener).close();
      fixture.jcacheLoading().inFlight.add(
          CompletableFuture.failedFuture(new IllegalStateException()));

      var configuration = new MutableCacheEntryListenerConfiguration<>(
          /* listenerFactory= */ () -> listener, /* filterFactory= */ () -> event -> true,
          /* isOldValueRequired= */ false, /* isSynchronous= */ false);
      fixture.jcacheLoading().registerCacheEntryListener(configuration);

      fixture.jcacheLoading().close();
      verify(expiry, atLeastOnce()).close();
      verify(loader, atLeastOnce()).close();
      verify(writer, atLeastOnce()).close();
      verify(listener, atLeastOnce()).close();
    } catch (IOException expected) { /* ignored */ }
  }

  @Test
  void close_resourcesThrowSameInstance_doesNotSelfSuppress() throws IOException {
    var failure = new IOException();
    try (CloseableCacheLoader loader = Mockito.mock();
        CloseableCacheWriter writer = Mockito.mock();
        CloseableExpiryPolicy expiry = Mockito.mock();
        var fixture = jcacheFixture(loader, writer, expiry)) {
      // Two resources throw the same Throwable instance on close(). tryClose must guard against
      // error.addSuppressed(error), which throws IllegalArgumentException ("Self-suppression not
      // permitted") and would abort close() before the remaining resources are released.
      doThrow(failure).when(expiry).close();
      doThrow(failure).when(writer).close();

      fixture.jcacheLoading().close();

      verify(expiry, atLeastOnce()).close();
      verify(writer, atLeastOnce()).close();

      // Reset so this test's own try-with-resources cleanup does not self-suppress on the shared
      // instance (the JDK's try-with-resources combines close failures the same way tryClose does).
      Mockito.doNothing().when(expiry).close();
      Mockito.doNothing().when(writer).close();
    }
  }

  static Stream<Arguments> exceptions() {
    return Stream.of(
        arguments(new IllegalStateException(), IllegalStateException.class, false),
        arguments(new NullPointerException(), NullPointerException.class, false),
        arguments(new ClassCastException(), ClassCastException.class, false),
        arguments(new CacheException(), CacheException.class, false),
        arguments(new UncheckedTimeoutException(), CacheException.class, true));
  }

  @Test
  void close_interrupted() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      var neverCompletes = new CompletableFuture<@Nullable Void>();
      fixture.jcache().inFlight.add(neverCompletes);

      // Interrupt before close() so get() throws InterruptedException immediately
      Thread.currentThread().interrupt();
      fixture.jcache().close();

      // close() restores the interrupt flag via Thread.currentThread().interrupt()
      assertThat(Thread.interrupted()).isTrue();
    }
  }

  @Test
  void close_singlePass_closeablesInvokedOnce() throws IOException {
    try (CloseableCacheLoader loader = Mockito.mock();
        CloseableCacheWriter writer = Mockito.mock();
        CloseableExpiryPolicy expiry = Mockito.mock();
        CloseableCacheEntryListener listener = Mockito.mock();
        var fixture = jcacheFixture(loader, writer, expiry)) {
      var configuration = new MutableCacheEntryListenerConfiguration<>(
          /* listenerFactory= */ () -> listener, /* filterFactory= */ () -> event -> true,
          /* isOldValueRequired= */ false, /* isSynchronous= */ false);
      fixture.jcacheLoading().registerCacheEntryListener(configuration);

      fixture.jcacheLoading().close();
      verify(expiry).close();
      verify(loader).close();
      verify(writer).close();
      verify(listener).close();
    }
  }

  @Test
  @SuppressWarnings("try")
  void close_afterManagerClosed_releasesResources() throws IOException {
    try (CloseableExpiryPolicy expiry = Mockito.mock();
        var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      when(expiry.getExpiryForCreation()).thenReturn(ETERNAL);
      try (var orphan = new CacheProxy<Integer, Integer>("orphan", Runnable::run,
          fixture.cacheManager(), new CaffeineConfiguration<>(), Mockito.mock(), Mockito.mock(),
          Optional.empty(), expiry, Mockito.mock(), Mockito.mock())) {
        fixture.cacheManager().close();
        orphan.close();

        assertThat(orphan.isClosed()).isTrue();
        verify(expiry).close();
      }
    }
  }

  interface CloseableExpiryPolicy extends ExpiryPolicy, Closeable {}
  interface CloseableCacheLoader extends CacheLoader<Integer, Integer>, Closeable {}
  interface CloseableCacheWriter extends CacheWriter<Integer, Integer>, Closeable {}
  @SuppressWarnings("PMD.ImplicitFunctionalInterface")
  interface CloseableCacheEntryListener extends CacheEntryListener<Integer, Integer>, Closeable {}
}
