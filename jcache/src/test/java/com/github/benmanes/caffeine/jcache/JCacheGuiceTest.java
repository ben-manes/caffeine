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

import java.util.Map;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.annotation.CacheResolverFactory;
import javax.cache.annotation.CacheResult;
import javax.cache.configuration.Factory;
import javax.cache.configuration.FactoryBuilder;
import javax.cache.integration.CacheLoader;
import javax.cache.spi.CachingProvider;

import org.jsr107.ri.annotations.DefaultCacheResolverFactory;
import org.jsr107.ri.annotations.guice.module.CacheAnnotationsModule;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.configuration.FactoryCreator;
import com.github.benmanes.caffeine.jcache.configuration.TypesafeConfigurator;
import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.util.Modules;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JCacheGuiceTest {
  @Inject CacheManager cacheManager;
  @Inject Service service;

  @BeforeMethod
  public void beforeMethod() {
    Module module = Modules.override(new CacheAnnotationsModule()).with(new CaffeineJCacheModule());
    Guice.createInjector(module).injectMembers(this);
  }

  @AfterClass
  public void afterClass() {
    TypesafeConfigurator.setFactoryCreator(FactoryBuilder::factoryOf);
  }

  @Test
  public void factory() {
    Cache<Integer, Integer> cache = cacheManager.getCache("guice");
    Map<Integer, Integer> result = cache.getAll(ImmutableSet.of(1, 2, 3));
    assertThat(result, is(ImmutableMap.of(1, 1, 2, 2, 3, 3)));
  }

  @Test
  public void annotations() {
    for (int i = 0; i < 10; i++) {
      assertThat(service.get(), is(1));
    }
    assertThat(service.times, is(1));
  }

  static class Service {
    int times;

    @CacheResult(cacheName = "annotations")
    public Integer get() {
      return ++times;
    }
  }

  public static final class InjectedCacheLoader implements CacheLoader<Integer, Integer> {
    private final Service service;

    @Inject
    InjectedCacheLoader(Service service) {
      this.service = service;
    }

    @Override
    public Integer load(Integer key) {
      return ++service.times;
    }

    @Override
    public Map<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
      return Maps.toMap(ImmutableSet.copyOf(keys), this::load);
    }
  }

  static final class GuiceFactoryCreator implements FactoryCreator {
    final Injector injector;

    @Inject
    GuiceFactoryCreator(Injector injector) {
      this.injector = injector;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Factory<T> factoryOf(String className) {
      try {
        Class<T> clazz = (Class<T>) Class.forName(className);
        return injector.getProvider(clazz)::get;
      } catch (ClassNotFoundException e) {
        throw new RuntimeException(e);
      }
    }
  }

  static final class CaffeineJCacheModule extends AbstractModule {

    @Override protected void configure() {
      configureCachingProvider();
      requestStaticInjection(TypesafeConfigurator.class);
      bind(FactoryCreator.class).to(GuiceFactoryCreator.class);
    }

    /** Resolves the annotations to the provider as multiple are on the IDE's classpath. */
    void configureCachingProvider() {
      CachingProvider provider = Caching.getCachingProvider(
          CaffeineCachingProvider.class.getName());
      CacheManager cacheManager = provider.getCacheManager(
          provider.getDefaultURI(), provider.getDefaultClassLoader());
      bind(CacheResolverFactory.class).toInstance(new DefaultCacheResolverFactory(cacheManager));
      bind(CacheManager.class).toInstance(cacheManager);
    }
  }
}
