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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.EnumSet;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import javax.cache.Cache;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.MutableConfiguration;
import javax.management.ObjectName;
import javax.management.OperationsException;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.facebook.infer.annotation.SuppressLint;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.configuration.TypesafeConfigurator;
import com.typesafe.config.ConfigFactory;

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
      checkConfigurationJmx(fixture.cacheManager().getCache("test-cache"));
    }
  }

  @Test
  void enableManagement_absent() {
    try (var fixture = JCacheFixture.builder().build()) {
      fixture.cacheManager().enableManagement("absent", true);
      assertThat(fixture.cacheManager().getCache("absent")).isNull();
    }
  }

  @Test
  void enableStatistics_absent() {
    try (var fixture = JCacheFixture.builder().build()) {
      fixture.cacheManager().enableStatistics("absent", true);
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
  void isClosed() throws InterruptedException, ExecutionException {
    try (var fixture = JCacheFixture.builder().build()) {
      @SuppressWarnings("PMD.CloseResource")
      var manager = (CacheManagerImpl) fixture.cachingProvider().getCacheManager(
          URI.create(getClass().getName()), fixture.cachingProvider().getDefaultClassLoader());
      var task = new FutureTask<>(manager::close, /* result= */ null);
      var thread = new Thread(task);
      synchronized (manager.lock) {
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

  private static void checkConfigurationJmx(Cache<?, ?> cache) throws OperationsException {
    @SuppressWarnings("unchecked")
    CompleteConfiguration<?, ?> configuration = cache.getConfiguration(CompleteConfiguration.class);
    assertThat(configuration.isManagementEnabled()).isTrue();
    assertThat(configuration.isStatisticsEnabled()).isTrue();
    ManagementFactory.getPlatformMBeanServer().getObjectInstance( new ObjectName(
        String.format(US, "javax.cache:Cache=%s,CacheManager=%s,type=CacheStatistics",
            cache.getName(), cache.getCacheManager().getCachingProvider().getClass().getName())));
  }
}
