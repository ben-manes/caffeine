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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.configuration.Configuration;
import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryListener;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheLoaderException;
import javax.cache.integration.CacheWriter;
import javax.cache.integration.CompletionListener;
import javax.cache.integration.CompletionListenerFuture;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.processor.Action;
import com.github.benmanes.caffeine.jcache.processor.EntryProcessorEntry;
import com.google.common.collect.ImmutableMap;
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
  void loadAll_cacheLoaderException() {
    try (var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), Mockito.mock())) {
      var e = new CacheLoaderException();
      CompletionListener completionListener = Mockito.mock();
      doThrow(e).when(completionListener).onCompletion();
      fixture.jcache().loadAll(KEYS, /* replaceExistingValues= */ true, completionListener);
      verify(completionListener).onException(e);
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
  void getWriteExpireTime_exception() throws IOException {
    try (CloseableExpiryPolicy expiry = Mockito.mock();
        var fixture = jcacheFixture(Mockito.mock(), Mockito.mock(), expiry)) {
      when(expiry.getExpiryForCreation()).thenThrow(IllegalStateException.class);
      long time = fixture.jcache().getWriteExpireTimeMillis(true);
      assertThat(time).isEqualTo(Long.MIN_VALUE);
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

  static Stream<Arguments> exceptions() {
    return Stream.of(
        arguments(new IllegalStateException(), IllegalStateException.class, false),
        arguments(new NullPointerException(), NullPointerException.class, false),
        arguments(new ClassCastException(), ClassCastException.class, false),
        arguments(new CacheException(), CacheException.class, false),
        arguments(new UncheckedTimeoutException(), CacheException.class, true));
  }

  interface CloseableExpiryPolicy extends ExpiryPolicy, Closeable {}
  interface CloseableCacheLoader extends CacheLoader<Integer, Integer>, Closeable {}
  interface CloseableCacheWriter extends CacheWriter<Integer, Integer>, Closeable {}
  @SuppressWarnings("PMD.ImplicitFunctionalInterface")
  interface CloseableCacheEntryListener extends CacheEntryListener<Integer, Integer>, Closeable {}
}
