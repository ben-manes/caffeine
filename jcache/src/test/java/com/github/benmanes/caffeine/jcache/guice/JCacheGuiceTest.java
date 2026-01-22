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
package com.github.benmanes.caffeine.jcache.guice;

import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;

import java.util.function.Consumer;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.annotation.CacheResolverFactory;
import javax.cache.annotation.CacheResult;
import javax.cache.configuration.Factory;
import javax.cache.configuration.FactoryBuilder;
import javax.cache.integration.CacheLoader;

import org.jsr107.ri.annotations.DefaultCacheResolverFactory;
import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.jcache.JCacheFixture;
import com.github.benmanes.caffeine.jcache.configuration.FactoryCreator;
import com.github.benmanes.caffeine.jcache.configuration.TypesafeConfigurator;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.util.Modules;

import jakarta.inject.Inject;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class JCacheGuiceTest {

  @Test
  void factory() {
    runTest(injector -> {
      var cacheManager = injector.getInstance(CacheManager.class);
      Cache<Integer, Integer> cache = cacheManager.getCache("guice");
      var result = cache.getAll(ImmutableSet.of(1, 2, 3));
      assertThat(result).containsExactly(1, 1, 2, 2, 3, 3);
    });
  }

  @Test
  void annotations() {
    runTest(injector -> {
      var service = injector.getInstance(Service.class);
      for (int i = 0; i < 10; i++) {
        assertThat(service.get()).isEqualTo(1);
      }
      assertThat(service.times).isEqualTo(1);
    });
  }

  private static void runTest(Consumer<Injector> test) {
    try (var fixture = JCacheFixture.builder().build()) {
      var module = Modules.override(new JakartaCacheModule())
          .with(new CaffeineJCacheModule(fixture.cacheManager()));
      test.accept(Guice.createInjector(module));
    } finally {
      TypesafeConfigurator.setFactoryCreator(FactoryBuilder::factoryOf);
    }
  }

  public static class Service {
    int times;

    @CacheResult(cacheName = "annotations")
    public Integer get() {
      return ++times;
    }
  }

  public static final class InjectedCacheLoader implements CacheLoader<Integer, Integer> {
    private final Service service;

    @Inject
    private InjectedCacheLoader(Service service) {
      this.service = service;
    }

    @Override
    public Integer load(Integer key) {
      return ++service.times;
    }

    @Override
    public ImmutableMap<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
      return Maps.toMap(ImmutableSet.copyOf(keys), this::load);
    }
  }

  private static final class GuiceFactoryCreator implements FactoryCreator {
    private final Injector injector;

    @Inject
    private GuiceFactoryCreator(Injector injector) {
      this.injector = injector;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Factory<T> factoryOf(String className) {
      try {
        var clazz = (Class<T>) Class.forName(className);
        return injector.getProvider(clazz)::get;
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private static final class CaffeineJCacheModule extends AbstractModule {
    private final CacheManager cacheManager;

    private CaffeineJCacheModule(CacheManager cacheManager) {
      this.cacheManager = requireNonNull(cacheManager);
    }

    @Override protected void configure() {
      requestStaticInjection(TypesafeConfigurator.class);

      bind(CacheManager.class).toInstance(cacheManager);
      bind(FactoryCreator.class).to(GuiceFactoryCreator.class);
      bind(CacheResolverFactory.class).toInstance(new DefaultCacheResolverFactory(cacheManager));
    }
  }
}
