/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.jcache;

import static com.github.benmanes.caffeine.jcache.JCacheFixture.await;
import static com.google.common.truth.Truth.assertThat;
import static java.lang.Thread.State.BLOCKED;
import static java.lang.Thread.State.WAITING;
import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.cache.Cache;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.MutableConfiguration;
import javax.management.ObjectName;
import javax.management.OperationsException;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.facebook.infer.annotation.SuppressLint;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.configuration.TypesafeConfigurator;
import com.typesafe.config.ConfigFactory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author eiden (Christoffer Eide)
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class CacheManagerTest {

  @Test
  void jmxBeanIsRegistered_createCache() throws OperationsException {
    try (var fixture = JCacheFixture.builder().build()) {
      checkConfigurationJmx(fixture.cacheManager().createCache("cache-not-in-config-file",
          TypesafeConfigurator.from(ConfigFactory.load(), "test-cache").orElseThrow()));
    }
  }

  @Test
  void jmxBeanIsRegistered_getCache() throws OperationsException {
    try (var fixture = JCacheFixture.builder().build()) {
      checkConfigurationJmx(requireNonNull(fixture.cacheManager().getCache("test-cache")));
    }
  }

  @Test
  void enableManagement_absent() {
    try (var fixture = JCacheFixture.builder().build()) {
      fixture.cacheManager().enableManagement("absent", /* enabled= */ true);
      assertThat(fixture.cacheManager().getCache("absent")).isNull();
    }
  }

  @Test
  void enableStatistics_absent() {
    try (var fixture = JCacheFixture.builder().build()) {
      fixture.cacheManager().enableStatistics("absent", /* enabled= */ true);
      assertThat(fixture.cacheManager().getCache("absent")).isNull();
    }
  }

  @Test
  void maximumWeight_noWeigher() {
    try (var fixture = JCacheFixture.builder().build()) {
      var config = new CaffeineConfiguration<>().setMaximumWeight(OptionalLong.of(1_000));
      assertThrows(IllegalStateException.class, () ->
          fixture.cacheManager().createCache("absent", config));
    }
  }

  @Test
  void isReadThrough() {
    try (var fixture = JCacheFixture.builder().build()) {
      var config1 = new MutableConfiguration<>().setReadThrough(false);
      assertThat(fixture.cacheManager().createCache(getClass() + "-1", config1))
          .isNotInstanceOf(LoadingCacheProxy.class);

      var config2 = new MutableConfiguration<>().setReadThrough(true);
      assertThat(fixture.cacheManager().createCache(getClass() + "-2", config2))
          .isNotInstanceOf(LoadingCacheProxy.class);

      var config3 = new MutableConfiguration<>()
          .setCacheLoaderFactory(Mockito::mock)
          .setReadThrough(true);
      assertThat(fixture.cacheManager().createCache(getClass() + "-3", config3))
          .isInstanceOf(LoadingCacheProxy.class);
    }
  }

  @Test
  @SuppressLint("THREAD_SAFETY_VIOLATION")
  void isClosed() throws IllegalAccessException, InterruptedException, ExecutionException {
    try (var fixture = JCacheFixture.builder().build()) {
      @SuppressWarnings("PMD.CloseResource")
      var manager = (CacheManagerImpl) fixture.cachingProvider().getCacheManager(
          URI.create(getClass().getName()), fixture.cachingProvider().getDefaultClassLoader());
      var task = new FutureTask<>(manager::close, /* result= */ null);
      var lock = FieldUtils.readField(manager, "lock", /* forceAccess= */ true);
      var thread = new Thread(task);
      synchronized (lock) {
        thread.start();
        var threadState = EnumSet.of(BLOCKED, WAITING);
        await().until(() -> threadState.contains(thread.getState()));
        manager.close();
        assertThat(manager.isClosed()).isTrue();
      }
      task.get();
      assertThat(manager.isClosed()).isTrue();
    }
  }

  @Test
  @SuppressLint("THREAD_SAFETY_VIOLATION")
  void close_classLoaderGced_doesNotClosePeerManager() {
    try (var fixture = JCacheFixture.builder().build()) {
      var customLoader = new ClassLoader() {};
      var sharedUri = URI.create("shared-" + getClass().getName());

      @SuppressWarnings("PMD.CloseResource")
      var managerA = (CacheManagerImpl) fixture.cachingProvider()
          .getCacheManager(sharedUri, customLoader);
      @SuppressWarnings("PMD.CloseResource")
      var managerB = (CacheManagerImpl) fixture.cachingProvider().getCacheManager(
          sharedUri, fixture.cachingProvider().getDefaultClassLoader());
      assertThat(managerA).isNotSameInstanceAs(managerB);

      // Simulate managerA's custom ClassLoader being GC'd while managerA is still
      // strongly reachable. Pre-fix, managerA.close() would forward null to the
      // provider, which falls back to the default loader and closes managerB.
      managerA.classLoaderReference.clear();
      managerA.close();

      assertThat(managerA.isClosed()).isTrue();
      assertThat(managerB.isClosed()).isFalse();
      managerB.close();
    }
  }

  @Test
  @SuppressWarnings("PMD.CloseResource")
  void close_throwingCacheClose_continuesAndMarksClosed() {
    try (var fixture = JCacheFixture.builder().build()) {
      @SuppressWarnings("PMD.CloseResource")
      var manager = (CacheManagerImpl) fixture.cachingProvider().getCacheManager(
          URI.create(getClass().getName() + "-throwing-close"),
          fixture.cachingProvider().getDefaultClassLoader());

      CacheProxy<?, ?> bomb = Mockito.mock();
      Mockito.doThrow(new RuntimeException("boom")).when(bomb).close();
      Mockito.when(bomb.getName()).thenReturn("bomb");
      manager.caches.put("bomb", bomb);

      manager.close();

      // Per spec p.26: "If a Cache#close() call throws an exception, the exception
      // will be ignored. After executing this method, the isClosed() method will
      // return true."
      assertThat(manager.isClosed()).isTrue();
    }
  }

  @Test
  void close_clearsCachesAndIsIdempotent() {
    try (var fixture = JCacheFixture.builder().build()) {
      @SuppressWarnings("PMD.CloseResource")
      var manager = (CacheManagerImpl) fixture.cachingProvider().getCacheManager(
          URI.create(getClass().getName()), fixture.cachingProvider().getDefaultClassLoader());
      assertThat(manager.createCache("a", new MutableConfiguration<>())).isNotNull();
      assertThat(manager.createCache("b", new MutableConfiguration<>())).isNotNull();

      manager.close();
      assertThat(manager.isClosed()).isTrue();
      assertThat(manager.caches).isEmpty();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void createCache_completeConfiguration_withDefaultListeners() {
    var defaultConfigSource = TypesafeConfigurator.configSource();
    var overlay = ConfigFactory.parseString("caffeine.jcache.default.listeners = ["
        + "caffeine.jcache.listeners.test-listener,"
        + "caffeine.jcache.listeners.test-listener2]");
    var merged = overlay.withFallback(ConfigFactory.load());
    TypesafeConfigurator.setConfigSource(() -> merged);
    try (var fixture = JCacheFixture.builder().build();
         var cache = fixture.cacheManager().createCache(
             "complete-defaults", new MutableConfiguration<String, String>())) {
      assertThat(cache).isNotNull();
      var resolved = cache.getConfiguration(CompleteConfiguration.class);
      assertThat(resolved.getCacheEntryListenerConfigurations()).isEmpty();
    } finally {
      TypesafeConfigurator.setConfigSource(defaultConfigSource);
    }
  }

  @Test
  @SuppressLint("THREAD_SAFETY_VIOLATION")
  void close_blocksConcurrentCreateCache() throws Exception {
    try (var fixture = JCacheFixture.builder().build()) {
      @SuppressWarnings("PMD.CloseResource")
      var manager = (CacheManagerImpl) fixture.cachingProvider().getCacheManager(
          URI.create(getClass().getName()), fixture.cachingProvider().getDefaultClassLoader());
      var closeTask = new FutureTask<>(manager::close, /* result= */ null);
      var createTask = new FutureTask<Cache<Integer, Integer>>(() ->
          manager.createCache("racing", new MutableConfiguration<>()));
      var closeThread = new Thread(closeTask);
      var createThread = new Thread(createTask);

      var threadState = EnumSet.of(BLOCKED, WAITING);
      var lock = FieldUtils.readField(manager, "lock", /* forceAccess= */ true);
      synchronized (lock) {
        closeThread.start();
        await().until(() -> threadState.contains(closeThread.getState()));
        createThread.start();
        await().until(() -> threadState.contains(createThread.getState()));
      }

      closeTask.get();
      // createCache must serialize with close: either it lost the race and threw,
      // or it inserted a cache that close subsequently closed and removed
      try {
        createTask.get();
      } catch (ExecutionException expected) {
        assertThat(expected).hasCauseThat().isInstanceOf(IllegalStateException.class);
      }
      assertThat(manager.isClosed()).isTrue();
      assertThat(manager.caches).isEmpty();
    }
  }

  @Test
  @SuppressWarnings("try")
  void close_doesNotEvictSameNamedReplacement() {
    var configuration = new MutableConfiguration<>();
    try (var fixture = JCacheFixture.builder().build();
         var manager = (CacheManagerImpl) fixture.cachingProvider().getCacheManager(
            URI.create(getClass().getName()), fixture.cachingProvider().getDefaultClassLoader());
         var stale = (CacheProxy<?, ?>) manager.createCache("cache", configuration);
         var replacement = (CacheProxy<?, ?>) manager.createCache("spare", configuration)) {
      // A same-named replacement took over "cache" (the close/createCache race outcome); the stale
      // proxy closing itself must deregister by identity and leave the replacement intact.
      manager.caches.put("cache", replacement);
      stale.close();

      assertThat(stale.isClosed()).isTrue();
      assertThat(replacement.isClosed()).isFalse();
      assertThat(manager.caches).containsEntry("cache", replacement);
    }
  }

  @Test
  @SuppressLint("THREAD_SAFETY_VIOLATION")
  void enableManagement_concurrentClose_doesNotReregister() throws Exception {
    try (var fixture = JCacheFixture.builder().build();
         var cache = (CacheProxy<?, ?>) fixture.cacheManager()
             .createCache("jmx-race", new MutableConfiguration<>())) {
      var configuration = FieldUtils.readField(cache, "configuration", /* forceAccess= */ true);
      var task = new FutureTask<@Nullable Void>(() -> {
        cache.enableManagement(true);
        return null;
      });
      var blocked = EnumSet.of(BLOCKED, WAITING);
      var thread = new Thread(task);

      // Park enableManagement past its (now in-lock) close check, then close the cache underneath.
      synchronized (configuration) {
        thread.start();
        await().until(() -> blocked.contains(thread.getState()));
        FieldUtils.writeField(cache, "closed", true, /* forceAccess= */ true);
      }

      // The re-check inside the lock must observe the close and refuse to (re)register the MBean.
      var failure = assertThrows(ExecutionException.class, task::get);
      assertThat(failure).hasCauseThat().isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  @SuppressWarnings("try")
  void close_executor_shutdown() {
    try (var fixture = JCacheFixture.builder().build();
         var executor = new TrackingExecutorService();
         var cache = fixture.cacheManager().createCache("shutdownExecutor",
             new CaffeineConfiguration<>().setExecutorFactory(() -> executor))) {
      cache.close();
      assertThat(executor.shutdown.get()).isTrue();
      assertThat(executor.closed.get()).isFalse();
    }
  }

  @Test
  @SuppressWarnings("try")
  void close_executor_closeable() {
    try (var fixture = JCacheFixture.builder().build();
         var executor = new TrackingExecutor();
         var cache = fixture.cacheManager().createCache("closeExecutor",
             new CaffeineConfiguration<>().setExecutorFactory(() -> executor))) {
      cache.close();
      assertThat(executor.closed.get()).isTrue();
    }
  }

  private static void checkConfigurationJmx(Cache<?, ?> cache) throws OperationsException {
    @SuppressWarnings("unchecked")
    CompleteConfiguration<?, ?> configuration = cache.getConfiguration(CompleteConfiguration.class);
    assertThat(configuration.isManagementEnabled()).isTrue();
    assertThat(configuration.isStatisticsEnabled()).isTrue();
    ManagementFactory.getPlatformMBeanServer().getObjectInstance( new ObjectName(
        String.format(US, "javax.cache:Cache=%s,CacheManager=%s,type=CacheStatistics",
            cache.getName(), cache.getCacheManager().getCachingProvider().getClass().getName())));
  }

  private static final class TrackingExecutor implements Executor, AutoCloseable {
    final AtomicBoolean closed = new AtomicBoolean();

    @Override public void execute(Runnable command) {
      command.run();
    }
    @Override public void close() {
      closed.set(true);
    }
  }

  @SuppressFBWarnings("RI_REDUNDANT_INTERFACES")
  @SuppressWarnings({"PMD.UnnecessaryInterfaceDeclaration", "unused"})
  private static final class TrackingExecutorService
      extends AbstractExecutorService implements AutoCloseable {
    final AtomicBoolean shutdown = new AtomicBoolean();
    final AtomicBoolean closed = new AtomicBoolean();

    @Override public void execute(Runnable command) {
      command.run();
    }
    @Override public void shutdown() {
      shutdown.set(true);
    }
    @Override public List<Runnable> shutdownNow() {
      shutdown.set(true);
      return List.of();
    }
    @Override public boolean isShutdown() {
      return shutdown.get();
    }
    @Override public boolean isTerminated() {
      return shutdown.get();
    }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
    @Override public void close() {
      closed.set(true);
    }
  }
}
