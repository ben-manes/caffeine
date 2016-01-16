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
package com.github.benmanes.caffeine.jcache;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.annotation.CacheResolverFactory;
import javax.cache.annotation.CacheResult;
import javax.cache.spi.CachingProvider;

import org.jsr107.ri.annotations.DefaultCacheResolverFactory;
import org.jsr107.ri.annotations.guice.module.CacheAnnotationsModule;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.util.Modules;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JCacheGuiceTest {

  @Test
  public void sanity() {
    Module module = Modules.override(new CacheAnnotationsModule()).with(new CaffeineJCacheModule());
    Injector injector = Guice.createInjector(module);
    Service service = injector.getInstance(Service.class);
    for (int i = 0; i < 10; i++) {
      assertThat(service.get(), is(1));
    }
    assertThat(service.times, is(1));
  }

  public static class Service {
    int times;

    @CacheResult(cacheName = "guice")
    public Integer get() {
      return ++times;
    }
  }

  /** Resolves the annotations to the Caffeine provider as multiple are on the IDE classpath. */
  static final class CaffeineJCacheModule extends AbstractModule {

    @Override protected void configure() {
      CachingProvider provider = Caching.getCachingProvider(
          CaffeineCachingProvider.class.getName());
      CacheManager cacheManager = provider.getCacheManager(
          provider.getDefaultURI(), provider.getDefaultClassLoader());
      bind(CacheResolverFactory.class).toInstance(new DefaultCacheResolverFactory(cacheManager));
    }
  }
}
