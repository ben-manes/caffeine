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
package com.github.benmanes.caffeine.jcache.configuration;

import static com.github.benmanes.caffeine.jcache.configuration.TypesafeConfigurator.configSource;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.cache.Cache;
import javax.cache.Caching;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.expiry.Duration;
import javax.cache.expiry.EternalExpiryPolicy;
import javax.cache.expiry.ExpiryPolicy;

import org.testng.SkipException;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.copy.JavaSerializationCopier;
import com.google.common.collect.Iterables;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TypesafeConfigurationTest {
  final ClassLoader classloader = Thread.currentThread().getContextClassLoader();
  final ConfigSource defaultConfigSource = configSource();

  @BeforeMethod
  public void before() {
    TypesafeConfigurator.setConfigSource(defaultConfigSource);
  }

  @Test
  @SuppressWarnings("NullAway")
  public void setConfigSource_supplier() {
    TypesafeConfigurator.setConfigSource(() -> null);
    assertThat(configSource()).isNotSameInstanceAs(defaultConfigSource);

    assertThrows(NullPointerException.class, () ->
        TypesafeConfigurator.setConfigSource((Supplier<Config>) null));
  }

  @Test
  @SuppressWarnings("NullAway")
  public void setConfigSource_function() {
    TypesafeConfigurator.setConfigSource((uri, loader) -> null);
    assertThat(configSource()).isNotSameInstanceAs(defaultConfigSource);

    assertThrows(NullPointerException.class, () ->
        TypesafeConfigurator.setConfigSource((ConfigSource) null));
  }

  @Test
  @SuppressWarnings("NullAway")
  public void configSource_null() {
    assertThrows(NullPointerException.class, () -> configSource().get(null, null));
    assertThrows(NullPointerException.class, () -> configSource().get(null, classloader));
    assertThrows(NullPointerException.class, () -> configSource().get(URI.create(""), null));
  }

  @Test
  public void configSource_load() {
    assertThat(configSource().get(URI.create(getClass().getSimpleName()), classloader))
        .isSameInstanceAs(ConfigFactory.load());
    assertThat(configSource().get(URI.create(getClass().getName()), classloader))
        .isSameInstanceAs(ConfigFactory.load());
    assertThat(configSource().get(URI.create("rmi:/abc"), classloader))
        .isSameInstanceAs(ConfigFactory.load());
    assertThat(ConfigFactory.load().hasPath("caffeine.jcache.default.key-type")).isTrue();
    assertThat(ConfigFactory.load().hasPath("caffeine.jcache.test-cache")).isTrue();
  }

  @Test
  public void configSource_jar_present() {
    var inferred = configSource().get(URI.create("extra.properties"), classloader);
    assertThat(inferred.getInt("caffeine.jcache.jar.policy.maximum.size")).isEqualTo(500);
    assertThat(inferred.hasPath("caffeine.jcache.default.key-type")).isTrue();
    assertThat(inferred.hasPath("caffeine.jcache.test-cache")).isFalse();

    var explicit = configSource().get(getJarResource("extra.properties"), classloader);
    assertThat(explicit.getInt("caffeine.jcache.jar.policy.maximum.size")).isEqualTo(500);
    assertThat(explicit.hasPath("caffeine.jcache.default.key-type")).isTrue();
    assertThat(explicit.hasPath("caffeine.jcache.test-cache")).isFalse();
  }

  @Test
  public void configSource_jar_absent() {
    assertThrows(ConfigException.IO.class, () ->
        configSource().get(URI.create("extra-absent.conf"), classloader));

    var explicit = getJarResource("extra.properties").toString()
        .replace("extra.properties", "extra-absent.conf");
    assertThrows(ConfigException.IO.class, () ->
        configSource().get(URI.create(explicit), classloader));
  }

  @Test
  public void configSource_jar_invalid() {
    assertThrows(ConfigException.Parse.class, () ->
        configSource().get(URI.create("extra-invalid.conf"), classloader));

    assertThrows(ConfigException.BadPath.class, () ->
        configSource().get(URI.create("jar:invalid"), classloader));

    var explicit = getJarResource("extra-invalid.conf");
    assertThrows(ConfigException.Parse.class, () -> configSource().get(explicit, classloader));
  }

  @Test
  public void configSource_classpath_present() {
    var inferred = configSource().get(URI.create("custom.properties"), classloader);
    assertThat(inferred.getInt("caffeine.jcache.classpath.policy.maximum.size")).isEqualTo(500);
    assertThat(inferred.hasPath("caffeine.jcache.default.key-type")).isTrue();
    assertThat(inferred.hasPath("caffeine.jcache.test-cache")).isFalse();

    var explicit = configSource().get(URI.create("classpath:custom.properties"), classloader);
    assertThat(explicit.getInt("caffeine.jcache.classpath.policy.maximum.size")).isEqualTo(500);
    assertThat(explicit.hasPath("caffeine.jcache.default.key-type")).isTrue();
    assertThat(explicit.hasPath("caffeine.jcache.test-cache")).isFalse();
  }

  @Test
  public void configSource_classpath_absent() {
    assertThrows(ConfigException.IO.class, () ->
        configSource().get(URI.create("absent.conf"), classloader));
    assertThrows(ConfigException.IO.class, () ->
        configSource().get(URI.create("classpath:absent.conf"), classloader));
    assertThrows(ConfigException.IO.class, () ->
        configSource().get(URI.create("classpath:custom.properties"), new ClassLoader(null) {}));
  }

  @Test
  public void configSource_classpath_invalid() {
    assertThrows(ConfigException.Parse.class, () ->
        configSource().get(URI.create("invalid.conf"), classloader));
    assertThrows(ConfigException.Parse.class, () ->
        configSource().get(URI.create("classpath:invalid.conf"), classloader));
  }

  @Test
  public void configSource_file() throws URISyntaxException {
    var config = configSource().get(
        getClass().getResource("/custom.properties").toURI(), classloader);
    assertThat(config.getInt("caffeine.jcache.classpath.policy.maximum.size")).isEqualTo(500);
    assertThat(config.hasPath("caffeine.jcache.default.key-type")).isTrue();
    assertThat(config.hasPath("caffeine.jcache.test-cache")).isFalse();
  }

  @Test
  public void configSource_file_absent() {
    assertThrows(ConfigException.IO.class, () ->
        configSource().get(URI.create("file:/absent.conf"), classloader));
  }

  @Test
  public void configSource_file_invalid() {
    assertThrows(ConfigException.IO.class, () ->
        configSource().get(URI.create("file:/invalid.conf"), classloader));
  }

  @Test
  public void defaults() {
    CaffeineConfiguration<Integer, Integer> defaults =
        TypesafeConfigurator.defaults(ConfigFactory.load());
    assertThat(defaults.getKeyType()).isEqualTo(Object.class);
    assertThat(defaults.getValueType()).isEqualTo(Object.class);
    assertThat(defaults.getExecutorFactory().create()).isEqualTo(ForkJoinPool.commonPool());
    assertThat(defaults.getMaximumSize()).hasValue(500);
  }

  @Test
  public void cacheNames() {
    assertThat(TypesafeConfigurator.cacheNames(ConfigFactory.empty())).isEmpty();

    var names = TypesafeConfigurator.cacheNames(ConfigFactory.load());
    assertThat(names).containsExactly("default", "listeners", "osgi-cache", "invalid-cache",
        "test-cache", "test-cache-2", "test-cache-3", "test-cache-4", "guice");
  }

  @Test
  public void illegalPath() {
    assertThat(TypesafeConfigurator.from(ConfigFactory.load(), "#")).isEmpty();
  }

  @Test
  public void invalidCache() {
    assertThrows(IllegalStateException.class, () ->
        TypesafeConfigurator.from(ConfigFactory.load(), "invalid-cache"));
  }

  @Test
  public void testCache() {
    Optional<CaffeineConfiguration<Integer, Integer>> config =
        TypesafeConfigurator.from(ConfigFactory.load(), "test-cache");
    assertThat(config).isEqualTo(
        TypesafeConfigurator.from(ConfigFactory.load(), "test-cache"));
    checkTestCache(config.orElseThrow());
  }

  @Test
  public void testCache2() {
    Optional<CaffeineConfiguration<Integer, Integer>> config1 =
        TypesafeConfigurator.from(ConfigFactory.load(), "test-cache");
    Optional<CaffeineConfiguration<Integer, Integer>> config2 =
        TypesafeConfigurator.from(ConfigFactory.load(), "test-cache-2");
    assertThat(config1).isNotEqualTo(config2);

    var config = config2.orElseThrow();
    assertThat(config.getKeyType()).isAssignableTo(String.class);
    assertThat(config.getValueType()).isAssignableTo(Integer.class);
    assertThat(config.isNativeStatisticsEnabled()).isFalse();
    assertThat(config.getExpiryPolicyFactory().create().getExpiryForAccess()).isNull();
    assertThat(config.getExpiryFactory().orElseThrow().create()).isInstanceOf(TestExpiry.class);
    assertThat(config.getExecutorFactory().create()).isEqualTo(ForkJoinPool.commonPool());
    assertThat(config.getCacheWriter()).isNull();
  }

  @Test
  public void testCache3() {
    Optional<CaffeineConfiguration<Integer, Integer>> config3 =
        TypesafeConfigurator.from(ConfigFactory.load(), "test-cache-3");

    var config = config3.orElseThrow();
    assertThat(config.getKeyType()).isAssignableTo(String.class);
    assertThat(config.getValueType()).isAssignableTo(Integer.class);
    assertThat(config.getCacheEntryListenerConfigurations()).hasSize(1);
    assertThat(config.getExpiryPolicyFactory().create()).isInstanceOf(EternalExpiryPolicy.class);
  }

  @Test
  public void testCache4() {
    Optional<CaffeineConfiguration<Integer, Integer>> config4 =
        TypesafeConfigurator.from(ConfigFactory.load(), "test-cache-4");

    var config = config4.orElseThrow();
    var expiry = config.getExpiryPolicyFactory().create();
    assertThat(expiry.getExpiryForCreation()).isEqualTo(Duration.ETERNAL);
    assertThat(expiry.getExpiryForUpdate()).isEqualTo(Duration.FIVE_MINUTES);
    assertThat(expiry.getExpiryForAccess()).isEqualTo(Duration.TEN_MINUTES);
  }

  @Test
  public void getCache() {
    try (Cache<Integer, Integer> cache = Caching.getCachingProvider()
        .getCacheManager().getCache("test-cache")) {
      assertThat(cache).isNotNull();

      @SuppressWarnings("unchecked")
      CaffeineConfiguration<Integer, Integer> config =
          cache.getConfiguration(CaffeineConfiguration.class);
      checkTestCache(config);
    }
  }

  private URI getJarResource(String resourceName) {
    var url = classloader.getResource(resourceName);
    assertThat(url).isNotNull();

    try {
      var uri = url.toURI();
      if (!Objects.equals(uri.getScheme(), "jar")) {
        throw new SkipException("This test must be run through the build system");
      }
      return uri;
    } catch (URISyntaxException e) {
      throw new AssertionError(e);
    }
  }

  static void checkTestCache(CaffeineConfiguration<?, ?> config) {
    checkStoreByValue(config);
    checkListener(config);

    assertThat(config.getKeyType()).isEqualTo(Object.class);
    assertThat(config.getValueType()).isEqualTo(Object.class);
    assertThat(config.getExecutorFactory().create()).isInstanceOf(TestExecutor.class);
    assertThat(config.getSchedulerFactory().create()).isInstanceOf(TestScheduler.class);
    assertThat(config.getCacheWriter()).isInstanceOf(TestCacheWriter.class);
    assertThat(requireNonNull(config.getCacheLoaderFactory()).create())
        .isInstanceOf(TestCacheLoader.class);
    assertThat(config.isNativeStatisticsEnabled()).isTrue();
    assertThat(config.isStatisticsEnabled()).isTrue();
    assertThat(config.isManagementEnabled()).isTrue();

    checkSize(config);
    checkRefresh(config);
    checkLazyExpiration(config);
    checkEagerExpiration(config);
  }

  static void checkStoreByValue(CaffeineConfiguration<?, ?> config) {
    assertThat(config.isStoreByValue()).isTrue();
    assertThat(config.getCopierFactory().create()).isInstanceOf(JavaSerializationCopier.class);
  }

  static void checkListener(CompleteConfiguration<?, ?> config) {
    var listener = requireNonNull(Iterables.getOnlyElement(
        config.getCacheEntryListenerConfigurations()));
    assertThat(listener.getCacheEntryListenerFactory().create())
        .isInstanceOf(TestCacheEntryListener.class);
    assertThat(listener.getCacheEntryEventFilterFactory().create())
        .isInstanceOf(TestCacheEntryEventFilter.class);
    assertThat(listener.isSynchronous()).isTrue();
    assertThat(listener.isOldValueRequired()).isTrue();
  }

  static void checkLazyExpiration(CompleteConfiguration<?, ?> config) {
    ExpiryPolicy expiry = config.getExpiryPolicyFactory().create();
    assertThat(expiry.getExpiryForCreation()).isEqualTo(Duration.ONE_MINUTE);
    assertThat(expiry.getExpiryForUpdate()).isEqualTo(Duration.FIVE_MINUTES);
    assertThat(expiry.getExpiryForAccess()).isEqualTo(Duration.TEN_MINUTES);
  }

  static void checkEagerExpiration(CaffeineConfiguration<?, ?> config) {
    assertThat(config.getExpireAfterWrite()).hasValue(TimeUnit.MINUTES.toNanos(1));
    assertThat(config.getExpireAfterAccess()).hasValue(TimeUnit.MINUTES.toNanos(5));
  }

  static void checkRefresh(CaffeineConfiguration<?, ?> config) {
    assertThat(config.getRefreshAfterWrite()).hasValue(TimeUnit.SECONDS.toNanos(30));
  }

  static void checkSize(CaffeineConfiguration<?, ?> config) {
    assertThat(config.getMaximumSize()).isEmpty();
    assertThat(config.getMaximumWeight()).hasValue(1_000L);
    assertThat(config.getWeigherFactory().orElseThrow().create()).isInstanceOf(TestWeigher.class);
  }
}
