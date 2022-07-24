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
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.cache.spi.CachingProvider;

import org.testng.Assert;
import org.testng.annotations.Test;

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
  }

  @Test
  public void loadClass_notFound() {
    runWithClassloader(provider -> {
      try {
        provider.getDefaultClassLoader().loadClass("a.b.c");
        Assert.fail();
      } catch (ClassNotFoundException expected) {}
    });
  }

  @Test
  public void resource_found() {
    runWithClassloader(provider -> {
      assertThat(provider.getDefaultClassLoader().getResource("")).isNotNull();
    });
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

  private void runWithClassloader(Consumer<CachingProvider> consumer) {
    var provider = new AtomicReference<CachingProvider>();
    new Thread(() -> {
      Thread.currentThread().setContextClassLoader(new ClassLoader() {});
      provider.set(new CaffeineCachingProvider());
    }).start();
    await().untilAtomic(provider, is(not(nullValue())));

    Thread.currentThread().setContextClassLoader(new ClassLoader() {});
    try {
      consumer.accept(provider.get());
    } finally {
      Thread.currentThread().setContextClassLoader(null);
    }
  }
}
