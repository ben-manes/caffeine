/*
 * Copyright 2022 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.jcache.spi;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.cache.spi.CachingProvider;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.google.common.util.concurrent.Uninterruptibles;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeineCachingProviderTest {

  @Test
  public void loadClass_found() {
    runWithClassloader(provider -> {
      try {
        provider.getDefaultClassLoader().loadClass(Object.class.getName());
      } catch (ClassNotFoundException e) {
        Assert.fail("", e);
      }
    });

    var classLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(null);
    try (var provider = new CaffeineCachingProvider()) {
      provider.getDefaultClassLoader().loadClass(Object.class.getName());
    } catch (ClassNotFoundException e) {
      Assert.fail("", e);
    } finally {
      Thread.currentThread().setContextClassLoader(classLoader);
    }
  }

  @Test
  public void loadClass_notFound() {
    runWithClassloader(provider -> {
      assertThrows(ClassNotFoundException.class, () ->
          provider.getDefaultClassLoader().loadClass("a.b.c"));
    });
  }

  @Test
  public void resource_found() {
    runWithClassloader(provider -> {
      assertThat(provider.getDefaultClassLoader().getResource("")).isNotNull();
    });

    var classLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(null);
    try (var provider = new CaffeineCachingProvider()) {
      assertThat(provider.getDefaultClassLoader().getResource("")).isNotNull();
    } finally {
      Thread.currentThread().setContextClassLoader(classLoader);
    }
  }

  @Test
  public void resource_notFound() {
    runWithClassloader(provider -> {
      assertThat(provider.getDefaultClassLoader().getResource("a.b.c")).isNull();
    });
  }

  @Test
  public void resources_found() {
    runWithClassloader(provider -> {
      try {
        var resources = provider.getDefaultClassLoader().getResources("");
        assertThat(Collections.list(resources)).isNotEmpty();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  @Test
  public void resources_notFound() {
    runWithClassloader(provider -> {
      try {
        var resources = provider.getDefaultClassLoader().getResources("a.b.c");
        assertThat(Collections.list(resources)).isEmpty();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  @Test
  public void osgi_getCache() {
    try (var provider = new CaffeineCachingProvider()) {
      provider.isOsgiComponent = true;
      var cacheManager = provider.getCacheManager(
          provider.getDefaultURI(), provider.getDefaultClassLoader());
      assertThat(cacheManager.getCache("test-cache", Object.class, Object.class)).isNotNull();
      assertThat(cacheManager.getCache("test-cache")).isNotNull();

      cacheManager.createCache("new-cache", new CaffeineConfiguration<>());
      assertThat(cacheManager.getCache("new-cache")).isNotNull();
    }
  }

  private void runWithClassloader(Consumer<CachingProvider> consumer) {
    var reference = new AtomicReference<CachingProvider>();
    var thread = new Thread(() -> {
      Thread.currentThread().setContextClassLoader(new ClassLoader() {});
      reference.set(new CaffeineCachingProvider());
    });
    thread.start();
    Uninterruptibles.joinUninterruptibly(thread, Duration.ofMinutes(1));

    var classLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(new ClassLoader() {});
    try (var provider = reference.get()) {
      consumer.accept(provider);
    } finally {
      Thread.currentThread().setContextClassLoader(classLoader);
    }
  }
}
