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

import static com.github.benmanes.caffeine.jcache.JCacheFixture.nullRef;
import static com.google.common.truth.Truth.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.cache.Cache;
import javax.cache.configuration.OptionalFeature;
import javax.cache.integration.CacheWriter;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.mockito.Mockito;

import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider.JCacheClassLoader;
import com.google.common.util.concurrent.Uninterruptibles;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class CaffeineCachingProviderTest {

  /* --------------- loadClass --------------- */

  @Test
  void loadClass_found_context() {
    runWithClassloader(context -> {
      var jcacheClassLoader = new JCacheClassLoader(context);
      assertThat(jcacheClassLoader.loadClass(Object.class.getName())).isNotNull();
    });
  }

  @Test
  void loadClass_found_class() throws ClassNotFoundException {
    var classLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(null);
    try {
      var jcacheClassLoader = new JCacheClassLoader(/* parent= */ null);
      assertThat(jcacheClassLoader.loadClass(Object.class.getName())).isNotNull();
    } finally {
      Thread.currentThread().setContextClassLoader(classLoader);
    }
  }

  @Test
  void loadClass_found_parent() {
    runWithClassloader(context -> {
      var parent = new ClassLoader() {
        @Override public Class<?> loadClass(String name) {
          return String.class;
        }
      };
      var jcacheClassLoader = new JCacheClassLoader(parent);
      assertThat(jcacheClassLoader.loadClass("a.b.c")).isNotNull();
    });
  }

  @Test
  void loadClass_notFound() {
    runWithClassloader(context -> {
      assertThrows(ClassNotFoundException.class, () -> {
        Thread.currentThread().setContextClassLoader(null);
        var jcacheClassLoader = new JCacheClassLoader(/* parent= */ null) {
          @Override @Nullable ClassLoader getClassClassLoader() {
            return null;
          }
        };
        jcacheClassLoader.loadClass("a.b.c");
      });
    });
  }

  @Test
  void loadClass_notFound_class() throws ClassNotFoundException {
    ClassLoader classloader = Mockito.mock();
    when(classloader.loadClass(anyString())).thenThrow(ClassNotFoundException.class);
    runWithClassloader(context -> {
      assertThrows(ClassNotFoundException.class, () -> {
        Thread.currentThread().setContextClassLoader(classloader);
        var jcacheClassLoader = new JCacheClassLoader(/* parent= */ null) {
          @Override ClassLoader getClassClassLoader() {
            return classloader;
          }
        };
        jcacheClassLoader.loadClass("a.b.c");
      });
    });
  }

  @Test
  void loadClass_notFound_parent_context() throws ClassNotFoundException {
    ClassLoader classloader = Mockito.mock();
    when(classloader.loadClass(anyString())).thenThrow(ClassNotFoundException.class);
    runWithClassloader(context -> {
      assertThrows(ClassNotFoundException.class, () -> {
        Thread.currentThread().setContextClassLoader(classloader);
        var jcacheClassLoader = new JCacheClassLoader(classloader);
        jcacheClassLoader.loadClass("a.b.c");
      });
    });
  }

  @Test
  void loadClass_notFound_parent_class() throws ClassNotFoundException {
    ClassLoader classloader = Mockito.mock();
    when(classloader.loadClass(anyString())).thenThrow(ClassNotFoundException.class);
    runWithClassloader(context -> {
      assertThrows(ClassNotFoundException.class, () -> {
        Thread.currentThread().setContextClassLoader(null);
        var jcacheClassLoader = new JCacheClassLoader(classloader) {
          @Override ClassLoader getClassClassLoader() {
            return classloader;
          }
        };
        jcacheClassLoader.loadClass("a.b.c");
      });
    });
  }

  @Test
  void loadClass_notFound_parent() {
    runWithClassloader(context -> {
      assertThrows(ClassNotFoundException.class, () -> {
        try (var provider = new CaffeineCachingProvider()) {
          Thread.currentThread().setContextClassLoader(provider.getDefaultClassLoader());
          var parent = new ClassLoader() {
            @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
              throw new ClassNotFoundException(name);
            }
          };
          var jcacheClassLoader = new JCacheClassLoader(parent);
          jcacheClassLoader.loadClass("a.b.c");
        }
      });
    });
  }

  /* --------------- resource --------------- */

  @Test
  void resource_found_context() {
    var classLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(null);
    try {
      var jcacheClassLoader = new JCacheClassLoader(/* parent= */ null);
      assertThat(jcacheClassLoader.getResource("")).isNotNull();
    } finally {
      Thread.currentThread().setContextClassLoader(classLoader);
    }
  }

  @Test
  void resource_found_class() {
    runWithClassloader(context -> {
      var jcacheClassLoader = new JCacheClassLoader(context);
      assertThat(jcacheClassLoader.getResource("")).isNotNull();
    });
  }

  @Test
  void resource_found_parent() {
    runWithClassloader(context -> {
      var url = Mockito.mock(URL.class);
      var parent = new ClassLoader() {
        @Override public URL getResource(String name) {
          return url;
        }
      };
      var jcacheClassLoader = new JCacheClassLoader(parent);
      assertThat(jcacheClassLoader.getResource("a.b.c")).isSameInstanceAs(url);
    });
  }

  @Test
  void resource_notFound() {
    runWithClassloader(context -> {
      Thread.currentThread().setContextClassLoader(null);
      var jcacheClassLoader = new JCacheClassLoader(/* parent= */ null) {
        @Override @Nullable ClassLoader getClassClassLoader() {
          return null;
        }
      };
      assertThat(jcacheClassLoader.getResource("a.b.c")).isNull();
    });
  }

  @Test
  void resource_notFound_class() {
    ClassLoader classloader = Mockito.mock();
    runWithClassloader(context -> {
      Thread.currentThread().setContextClassLoader(classloader);
      var jcacheClassLoader = new JCacheClassLoader(/* parent= */ null) {
        @Override @Nullable ClassLoader getClassClassLoader() {
          return classloader;
        }
      };
      assertThat(jcacheClassLoader.getResource("a.b.c")).isNull();
    });
  }

  @Test
  void resource_notFound_parent_context() {
    ClassLoader classloader = Mockito.mock();
    runWithClassloader(context -> {
      Thread.currentThread().setContextClassLoader(classloader);
      var jcacheClassLoader = new JCacheClassLoader(classloader);
      assertThat(jcacheClassLoader.getResource("a.b.c")).isNull();
    });
  }

  @Test
  void resource_notFound_parent_class() {
    ClassLoader classloader = Mockito.mock();
    runWithClassloader(context -> {
      Thread.currentThread().setContextClassLoader(null);
      var jcacheClassLoader = new JCacheClassLoader(classloader) {
        @Override @Nullable ClassLoader getClassClassLoader() {
          return classloader;
        }
      };
      assertThat(jcacheClassLoader.getResource("a.b.c")).isNull();
    });
  }

  @Test
  void resource_notFound_parent() {
    runWithClassloader(context -> {
      try (var provider = new CaffeineCachingProvider()) {
        Thread.currentThread().setContextClassLoader(provider.getDefaultClassLoader());
        ClassLoader classloader = Mockito.mock();
        var jcacheClassLoader = new JCacheClassLoader(classloader);
        assertThat(jcacheClassLoader.getResource("a.b.c")).isNull();
      }
    });
  }

  /* --------------- resources --------------- */

  @Test
  void resources_found_context() throws IOException {
    var classLoader = Thread.currentThread().getContextClassLoader();
    try {
      var resources = List.of(Mockito.mock(URL.class));
      Thread.currentThread().setContextClassLoader(new ClassLoader() {
        @Override public Enumeration<URL> getResources(String name) {
          return Collections.enumeration(resources);
        }
      });
      var jcacheClassLoader = new JCacheClassLoader(/* parent= */ null);
      assertThat(Collections.list(jcacheClassLoader.getResources("a.b.c"))).isEqualTo(resources);
    } finally {
      Thread.currentThread().setContextClassLoader(classLoader);
    }
  }

  @Test
  void resources_found_class() throws IOException {
    var classLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(null);
    try {
      var jcacheClassLoader = new JCacheClassLoader(/* parent= */ null);
      var resources = jcacheClassLoader.getResources("");
      assertThat(Collections.list(resources)).isNotEmpty();
    } finally {
      Thread.currentThread().setContextClassLoader(classLoader);
    }
  }

  @Test
  void resources_found_parent() {
    runWithClassloader(context -> {
      try {
        var resources = List.of(Mockito.mock(URL.class));
        var parent = new ClassLoader() {
          @Override public Enumeration<URL> getResources(String name) {
            return Collections.enumeration(resources);
          }
        };
        var jcacheClassLoader = new JCacheClassLoader(parent);
        assertThat(Collections.list(jcacheClassLoader.getResources("a.b.c"))).isEqualTo(resources);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  @Test
  void resources_notFound() {
    runWithClassloader(context -> {
      try {
        Thread.currentThread().setContextClassLoader(null);
        var jcacheClassLoader = new JCacheClassLoader(/* parent= */ null) {
          @Override @Nullable ClassLoader getClassClassLoader() {
            return null;
          }
        };
        var resources = jcacheClassLoader.getResources("a.b.c");
        assertThat(Collections.list(resources)).isEmpty();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  @Test
  void resources_notFound_class() throws IOException {
    ClassLoader classloader = Mockito.mock();
    when(classloader.getResources(anyString())).thenReturn(Collections.emptyEnumeration());
    runWithClassloader(context -> {
      try {
        Thread.currentThread().setContextClassLoader(classloader);
        var jcacheClassLoader = new JCacheClassLoader(/* parent= */ null) {
          @Override ClassLoader getClassClassLoader() {
            return classloader;
          }
        };
        var resources = jcacheClassLoader.getResources("a.b.c");
        assertThat(Collections.list(resources)).isEmpty();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  @Test
  void resources_notFound_parent_context() throws IOException {
    ClassLoader classloader = Mockito.mock();
    when(classloader.getResources(anyString())).thenReturn(Collections.emptyEnumeration());
    runWithClassloader(context -> {
      try {
        Thread.currentThread().setContextClassLoader(classloader);
        var jcacheClassLoader = new JCacheClassLoader(classloader);
        var resources = jcacheClassLoader.getResources("a.b.c");
        assertThat(Collections.list(resources)).isEmpty();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  @Test
  void resources_notFound_parent_class() throws IOException {
    ClassLoader classloader = Mockito.mock();
    when(classloader.getResources(anyString())).thenReturn(Collections.emptyEnumeration());
    runWithClassloader(context -> {
      try {
        Thread.currentThread().setContextClassLoader(null);
        var jcacheClassLoader = new JCacheClassLoader(classloader) {
          @Override ClassLoader getClassClassLoader() {
            return classloader;
          }
        };
        var resources = jcacheClassLoader.getResources("a.b.c");
        assertThat(Collections.list(resources)).isEmpty();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  @Test
  void resources_notFound_parent() throws IOException {
    ClassLoader classloader = Mockito.mock();
    when(classloader.getResources(anyString())).thenReturn(Collections.emptyEnumeration());
    runWithClassloader(context -> {
      try (var provider = new CaffeineCachingProvider()) {
        Thread.currentThread().setContextClassLoader(provider.getDefaultClassLoader());
        var jcacheClassLoader = new JCacheClassLoader(classloader);
        var resources = jcacheClassLoader.getResources("a.b.c");
        assertThat(Collections.list(resources)).isEmpty();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  /* --------------- Miscellaneous --------------- */

  @Test
  void osgi_getCache() {
    try (var provider = new CaffeineCachingProvider()) {
      provider.isOsgiComponent = true;
      try (var cacheManager = provider.getCacheManager(
          provider.getDefaultURI(), provider.getDefaultClassLoader())) {
        assertThat(cacheManager.getCache("test-cache", Object.class, Object.class)).isNotNull();
        assertThat(cacheManager.getCache("test-cache")).isNotNull();
        try (var cache = cacheManager.createCache("new-cache", new CaffeineConfiguration<>())) {
          assertThat(cacheManager.getCache("new-cache")).isSameInstanceAs(cache);
        }
      }
    }
  }

  @Test
  void isSupported() {
    try (var provider = new CaffeineCachingProvider()) {
      assertThat(provider.isSupported(OptionalFeature.STORE_BY_REFERENCE)).isTrue();
      assertThat(provider.isSupported(nullRef())).isFalse();
    }
  }

  /* --------------- close --------------- */

  @Test
  void close_concurrentProviderAndManager_noDeadlock() throws InterruptedException {
    var inWriterClose = new CountDownLatch(1);
    var releaseWriterClose = new CountDownLatch(1);
    try (var provider = new CaffeineCachingProvider();
         var cacheManager = provider.getCacheManager(
             provider.getDefaultURI(), provider.getDefaultClassLoader());
         var writer = new BlockingCacheWriter<>(inWriterClose, releaseWriterClose)) {
      cacheManager.createCache("blocking",
          new CaffeineConfiguration<>().setCacheWriterFactory(() -> writer));

      // Holds the manager lock (closing the cache) while parked in writer.close().
      var managerClose = new Thread(cacheManager::close, "manager-close");
      managerClose.setDaemon(true);
      managerClose.start();
      assertThat(inWriterClose.await(10, TimeUnit.SECONDS)).isTrue();

      // Holds the registry, then blocks acquiring the manager lock held above.
      var providerClose = new Thread(provider::close, "provider-close");
      providerClose.setDaemon(true);
      providerClose.start();
      await().until(() -> providerClose.getState() == Thread.State.BLOCKED);

      releaseWriterClose.countDown();

      managerClose.join(TimeUnit.SECONDS.toMillis(10));
      providerClose.join(TimeUnit.SECONDS.toMillis(10));
      assertThat(managerClose.isAlive()).isFalse();
      assertThat(providerClose.isAlive()).isFalse();
    }
  }

  private static void runWithClassloader(ThrowingConsumer<ClassLoader> consumer) {
    var classLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(new ClassLoader() {});
    try {
      assertDoesNotThrow(() ->
          consumer.accept(Thread.currentThread().getContextClassLoader()));
    } finally {
      Thread.currentThread().setContextClassLoader(classLoader);
    }
  }

  private static final class BlockingCacheWriter<K, V> implements CacheWriter<K, V>, AutoCloseable {
    final CountDownLatch entered;
    final CountDownLatch release;

    BlockingCacheWriter(CountDownLatch entered, CountDownLatch release) {
      this.entered = entered;
      this.release = release;
    }
    @Override public void write(Cache.Entry<? extends K, ? extends V> entry) {
      throw new UnsupportedOperationException();
    }
    @Override public void writeAll(Collection<Cache.Entry<? extends K, ? extends V>> entries) {
      throw new UnsupportedOperationException();
    }
    @Override public void delete(Object key) {
      throw new UnsupportedOperationException();
    }
    @Override public void deleteAll(Collection<?> keys) {
      throw new UnsupportedOperationException();
    }
    @Override public void close() {
      entered.countDown();
      Uninterruptibles.awaitUninterruptibly(release);
    }
  }
}
