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

import static com.google.common.truth.Truth.assertThat;
import static java.lang.Thread.State.BLOCKED;
import static java.lang.Thread.State.WAITING;
import static javax.cache.expiry.Duration.ETERNAL;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.Closeable;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.configuration.Configuration;
import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryListener;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheLoaderException;
import javax.cache.integration.CacheWriter;
import javax.cache.integration.CompletionListener;

import org.jspecify.annotations.Nullable;
import org.mockito.Mockito;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.processor.Action;
import com.github.benmanes.caffeine.jcache.processor.EntryProcessorEntry;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.UncheckedTimeoutException;

/**
 * @author github.com/kdombeck (Ken Dombeck)
 */
public final class CacheProxyTest extends AbstractJCacheTest {
  private final CloseableCacheEntryListener listener = Mockito.mock();
  private final CloseableExpiryPolicy expiry = Mockito.mock();
  private final CloseableCacheLoader loader = Mockito.mock();
  private final CloseableCacheWriter writer = Mockito.mock();

  @Override
  protected CaffeineConfiguration<Integer, Integer> getConfiguration() {
    Mockito.reset(listener, expiry, loader, writer);
    when(expiry.getExpiryForCreation()).thenReturn(ETERNAL);

    var configuration = new CaffeineConfiguration<Integer, Integer>();
    configuration.setExecutorFactory(MoreExecutors::directExecutor);
    configuration.setExpiryPolicyFactory(() -> expiry);
    configuration.setCacheLoaderFactory(() -> loader);
    configuration.setCacheWriterFactory(() -> writer);
    configuration.setTickerFactory(() -> ticker::read);
    configuration.setStatisticsEnabled(true);
    configuration.setWriteThrough(true);
    configuration.setReadThrough(false);
    return configuration;
  }

  /** The loading configuration used by the test. */
  @Override
  protected CaffeineConfiguration<Integer, Integer> getLoadingConfiguration() {
    var configuration = getConfiguration();
    configuration.setReadThrough(true);
    return configuration;
  }

  @Test
  @SuppressWarnings({"NullAway", "ObjectToString", "unchecked"})
  public void getConfiguration_immutable() {
    var config = jcache.getConfiguration(CaffeineConfiguration.class);
    var type = UnsupportedOperationException.class;

    assertThrows(type, () -> config.getCacheEntryListenerConfigurations().iterator().remove());
    assertThrows(type, () -> config.addCacheEntryListenerConfiguration(null));
    assertThrows(type, () -> config.setCacheLoaderFactory(null));
    assertThrows(type, () -> config.setCacheWriterFactory(null));
    assertThrows(type, () -> config.setCopierFactory(null));
    assertThrows(type, () -> config.setExecutorFactory(null));
    assertThrows(type, () -> config.setExpireAfterAccess(OptionalLong.empty()));
    assertThrows(type, () -> config.setExpireAfterWrite(OptionalLong.empty()));
    assertThrows(type, () -> config.setExpiryFactory(Optional.empty()));
    assertThrows(type, () -> config.setExpiryPolicyFactory(null));
    assertThrows(type, () -> config.setManagementEnabled(false));
    assertThrows(type, () -> config.setMaximumSize(OptionalLong.empty()));
    assertThrows(type, () -> config.setMaximumWeight(OptionalLong.empty()));
    assertThrows(type, () -> config.setNativeStatisticsEnabled(false));
    assertThrows(type, () -> config.setReadThrough(false));
    assertThrows(type, () -> config.setRefreshAfterWrite(OptionalLong.empty()));
    assertThrows(type, () -> config.setSchedulerFactory(null));
    assertThrows(type, () -> config.setStatisticsEnabled(false));
    assertThrows(type, () -> config.setStoreByValue(false));
    assertThrows(type, () -> config.setTickerFactory(null));
    assertThrows(type, () -> config.setTypes(String.class, String.class));
    assertThrows(type, () -> config.setWeigherFactory(Optional.empty()));
    assertThrows(type, () -> config.setWriteThrough(false));

    assertThat(config).isEqualTo(jcacheConfiguration);
    assertThat(config.toString()).isEqualTo(jcacheConfiguration.toString());

    var configuration = new MutableCacheEntryListenerConfiguration<>(
        /* listenerFactory= */ () -> listener, /* filterFactory= */ () -> event -> true,
        /* isOldValueRequired= */ false, /* isSynchronous= */ false);
    jcache.registerCacheEntryListener(configuration);
    assertThat(config.getCacheEntryListenerConfigurations()).hasSize(0);

    var config2 = jcache.getConfiguration(CaffeineConfiguration.class);
    assertThat(config2).isNotEqualTo(config);
    assertThat(config2.getCacheEntryListenerConfigurations()
        .spliterator().estimateSize()).isEqualTo(1);
    assertThat(config2.getCacheEntryListenerConfigurations()).hasSize(1);
    assertThat(config2.getCacheEntryListenerConfigurations().toString())
        .isEqualTo(List.of(configuration).toString());
  }

  @Test
  public void loadAll_cacheLoaderException() {
    var e = new CacheLoaderException();
    CompletionListener completionListener = Mockito.mock();
    doThrow(e).when(completionListener).onCompletion();
    jcache.loadAll(keys, /* replaceExistingValues= */ true, completionListener);
    verify(completionListener).onException(e);
  }

  @Test(dataProvider = "getExceptions")
  public void get_exception(Exception underlying, Class<Exception> thrown, boolean wrapped) {
    LoadingCache<Object, @Nullable Expirable<Object>> cache = Mockito.mock();
    when(cache.getIfPresent(any())).thenThrow(underlying);
    try (var proxy = new LoadingCacheProxy<>("dummy", Runnable::run, cacheManager, Mockito.mock(),
        cache, Mockito.mock(), Mockito.mock(), expiry, Mockito.mock(), Mockito.mock())) {
      var actual = assertThrows(thrown, () -> proxy.get(KEY_1));
      if (wrapped) {
        assertThat(actual).hasCauseThat().isSameInstanceAs(underlying);
      } else {
        assertThat(actual).isSameInstanceAs(underlying);
      }
    }
  }

  @Test(dataProvider = "getExceptions")
  public void getAll_exception(Exception underlying, Class<Exception> thrown, boolean wrapped) {
    LoadingCache<Object, @Nullable Expirable<Object>> cache = Mockito.mock();
    when(cache.getAllPresent(any())).thenThrow(underlying);
    try (var proxy = new LoadingCacheProxy<>("dummy", Runnable::run, cacheManager, Mockito.mock(),
        cache, Mockito.mock(), Mockito.mock(), expiry, Mockito.mock(), Mockito.mock())) {
      var actual = assertThrows(thrown, () -> proxy.getAll(Set.of(KEY_1)));
      if (wrapped) {
        assertThat(actual).hasCauseThat().isSameInstanceAs(underlying);
      } else {
        assertThat(actual).isSameInstanceAs(underlying);
      }
    }
  }

  @Test
  public void get_unexpired() {
    checkReadWhenUnexpired(jcache, currentTime().plus(EXPIRY_DURATION).toMillis(),
        key -> assertThat(jcache.get(key)).isNull());
    assertThat(jcache.statistics.getCacheMisses()).isEqualTo(1);
  }

  @Test
  public void getLoading_unexpired() {
    checkReadWhenUnexpired(jcacheLoading, currentTime().plus(EXPIRY_DURATION).toMillis(),
        key -> assertThat(jcacheLoading.get(key)).isNotNull());
    assertThat(jcacheLoading.statistics.getCacheMisses()).isEqualTo(1);
  }

  @Test
  public void getAll_unexpired() {
    checkReadWhenUnexpired(jcache, currentTime().plus(EXPIRY_DURATION).toMillis(),
        key -> assertThat(jcache.getAll(Set.of(key))).isEqualTo(Map.of()));
    assertThat(jcache.statistics.getCacheMisses()).isEqualTo(1);
  }

  @Test
  public void getAll_partial() {
    jcacheLoading.put(KEY_1, VALUE_1);
    jcacheLoading.put(KEY_2, VALUE_2);
    when(loader.loadAll(any())).thenReturn(Map.of(KEY_3, VALUE_3));
    var result = jcacheLoading.getAll(entries.keySet());
    assertThat(result).containsExactlyEntriesIn(entries);
  }

  @Test
  public void containsKey_unexpired() {
    checkReadWhenUnexpired(jcache, currentTime().plus(EXPIRY_DURATION).toMillis(),
        key -> assertThat(jcache.containsKey(key)).isFalse());
    assertThat(jcache.statistics.getCacheMisses()).isEqualTo(0);
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

  @Test(groups = "isolated")
  @SuppressWarnings({"CheckReturnValue", "EnumOrdinal"})
  public void postProcess_unknownAction() {
    try (var actionTypes = Mockito.mockStatic(Action.class)) {
      var unknown = Mockito.mock(Action.class);
      when(unknown.ordinal()).thenReturn(6);
      actionTypes.when(Action::values).thenReturn(new Action[] {
          Action.NONE, Action.READ, Action.CREATED, Action.UPDATED,
          Action.LOADED, Action.DELETED, unknown});
      EntryProcessorEntry<Integer, Integer> entry = Mockito.mock();
      when(entry.getAction()).thenReturn(unknown);
      assertThrows(IllegalStateException.class, () -> jcache.postProcess(null, entry, 0));
    }
  }

  @Test
  public void postProcess_none() {
    var expirable = new Expirable<>(VALUE_1, currentTime().plus(EXPIRY_DURATION).toMillis());
    EntryProcessorEntry<Integer, Integer> entry = Mockito.mock();
    when(entry.getAction()).thenReturn(Action.NONE);
    assertThat(jcache.postProcess(expirable, entry, 0L)).isSameInstanceAs(expirable);
  }

  @Test
  public void copyValue_null() {
    assertThat(jcache.copyValue(null)).isNull();
  }

  @Test
  public void setAccessExpireTime_eternal() {
    when(expiry.getExpiryForAccess()).thenReturn(ETERNAL);
    var expirable = new Expirable<>(KEY_1, 0);
    jcache.setAccessExpireTime(KEY_1, expirable, 0);
    assertThat(expirable.getExpireTimeMillis()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  public void setAccessExpireTime_exception() {
    when(expiry.getExpiryForAccess()).thenThrow(IllegalStateException.class);
    var expirable = new Expirable<>(KEY_1, 0);
    jcache.setAccessExpireTime(KEY_1, expirable, 0);
    assertThat(expirable.getExpireTimeMillis()).isEqualTo(0);
  }

  @Test
  public void getWriteExpireTime_exception() {
    when(expiry.getExpiryForCreation()).thenThrow(IllegalStateException.class);
    long time = jcache.getWriteExpireTimeMillis(true);
    assertThat(time).isEqualTo(Long.MIN_VALUE);
  }

  @Test
  public void unwrap_fail() {
    assertThrows(IllegalArgumentException.class, () -> jcache.unwrap(CaffeineConfiguration.class));
  }

  @Test
  public void unwrap() {
    assertThat(jcache.unwrap(Cache.class)).isSameInstanceAs(jcache);
    assertThat(jcache.unwrap(CacheProxy.class)).isSameInstanceAs(jcache);
    assertThat(jcache.unwrap(com.github.benmanes.caffeine.cache.Cache.class))
        .isSameInstanceAs(jcache.cache);
  }

  @Test
  public void unwrap_configuration() {
    abstract class Dummy implements Configuration<Integer, Integer> {
      private static final long serialVersionUID = 1L;
    }
    assertThrows(IllegalArgumentException.class, () -> jcache.getConfiguration(Dummy.class));
  }

  @Test
  public void unwrap_entry() {
    jcache.put(KEY_1, VALUE_1);
    var item = jcache.iterator().next();
    assertThrows(IllegalArgumentException.class, () -> item.unwrap(String.class));
  }

  @Test
  public void close_fails() throws IOException {
    doThrow(IOException.class).when(expiry).close();
    doThrow(IOException.class).when(loader).close();
    doThrow(IOException.class).when(writer).close();
    doThrow(IOException.class).when(listener).close();
    jcacheLoading.inFlight.add(CompletableFuture.failedFuture(new IllegalStateException()));

    var configuration = new MutableCacheEntryListenerConfiguration<>(
        /* listenerFactory= */ () -> listener, /* filterFactory= */ () -> event -> true,
        /* isOldValueRequired= */ false, /* isSynchronous= */ false);
    jcacheLoading.registerCacheEntryListener(configuration);

    jcacheLoading.close();
    verify(expiry, atLeastOnce()).close();
    verify(loader, atLeastOnce()).close();
    verify(writer, atLeastOnce()).close();
    verify(listener, atLeastOnce()).close();
  }

  @DataProvider(name = "getExceptions")
  public Object[][] providesGetExceptions() {
    return new Object[][] {
      { new IllegalStateException(), IllegalStateException.class, false },
      { new NullPointerException(), NullPointerException.class, false },
      { new ClassCastException(), ClassCastException.class, false },
      { new CacheException(), CacheException.class, false },
      { new UncheckedTimeoutException(), CacheException.class, true },
    };
  }

  interface CloseableExpiryPolicy extends ExpiryPolicy, Closeable {}
  interface CloseableCacheLoader extends CacheLoader<Integer, Integer>, Closeable {}
  interface CloseableCacheWriter extends CacheWriter<Integer, Integer>, Closeable {}
  @SuppressWarnings("PMD.ImplicitFunctionalInterface")
  interface CloseableCacheEntryListener extends CacheEntryListener<Integer, Integer>, Closeable {}
}
