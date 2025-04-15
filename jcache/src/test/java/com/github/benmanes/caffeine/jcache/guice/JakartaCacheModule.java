/*
 * Copyright 2025 Ben Manes. All Rights Reserved.
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

import javax.cache.annotation.CacheKeyGenerator;
import javax.cache.annotation.CachePut;
import javax.cache.annotation.CacheRemove;
import javax.cache.annotation.CacheRemoveAll;
import javax.cache.annotation.CacheResolverFactory;
import javax.cache.annotation.CacheResult;

import org.aopalliance.intercept.MethodInvocation;
import org.jsr107.ri.annotations.CacheContextSource;
import org.jsr107.ri.annotations.DefaultCacheKeyGenerator;
import org.jsr107.ri.annotations.DefaultCacheResolverFactory;
import org.jsr107.ri.annotations.guice.CacheLookupUtil;
import org.jsr107.ri.annotations.guice.CachePutInterceptor;
import org.jsr107.ri.annotations.guice.CacheRemoveAllInterceptor;
import org.jsr107.ri.annotations.guice.CacheRemoveEntryInterceptor;
import org.jsr107.ri.annotations.guice.CacheResultInterceptor;

import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.TypeLiteral;
import com.google.inject.matcher.Matchers;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * A version of {@link org.jsr107.ri.annotations.guice.module.CacheAnnotationsModule} to adapt the
 * the usages of javax.inject to their jakarta.inject counterparts.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class JakartaCacheModule extends AbstractModule {
  private static final TypeLiteral<CacheContextSource<MethodInvocation>> CACHE_LOOKUP =
      new TypeLiteral<>() {};

  @Override
  protected void configure() {
    bind(CACHE_LOOKUP).to(JakartaCacheLookup.class);
    bind(CacheKeyGenerator.class).to(DefaultCacheKeyGenerator.class);
    bind(CacheResolverFactory.class).to(DefaultCacheResolverFactory.class);

    interceptCachePut();
    interceptCacheResult();
    interceptCacheRemove();
    interceptCacheRemoveAll();
  }

  private void interceptCachePut() {
    var interceptor = new JakartaCachePutInterceptor();
    requestInjection(interceptor);
    bindInterceptor(Matchers.annotatedWith(CachePut.class), Matchers.any(), interceptor);
    bindInterceptor(Matchers.any(), Matchers.annotatedWith(CachePut.class), interceptor);
  }

  private void interceptCacheRemove() {
    var interceptor = new JakartaCacheRemoveEntryInterceptor();
    requestInjection(interceptor);
    bindInterceptor(Matchers.annotatedWith(CacheRemove.class), Matchers.any(), interceptor);
    bindInterceptor(Matchers.any(), Matchers.annotatedWith(CacheRemove.class), interceptor);
  }

  private void interceptCacheResult() {
    var interceptor = new JakartaCacheResultInterceptor();
    requestInjection(interceptor);
    bindInterceptor(Matchers.annotatedWith(CacheResult.class), Matchers.any(), interceptor);
    bindInterceptor(Matchers.any(), Matchers.annotatedWith(CacheResult.class), interceptor);
  }

  private void interceptCacheRemoveAll() {
    var interceptor = new JakartaCacheRemoveAllInterceptor();
    requestInjection(interceptor);
    bindInterceptor(Matchers.annotatedWith(CacheRemoveAll.class), Matchers.any(), interceptor);
    bindInterceptor(Matchers.any(), Matchers.annotatedWith(CacheRemoveAll.class), interceptor);
  }

  private static final class JakartaCachePutInterceptor extends CachePutInterceptor {
    @Inject void inject(CacheContextSource<MethodInvocation> cacheContextSource) {
      setCacheContextSource(cacheContextSource);
    }
  }

  private static final class JakartaCacheResultInterceptor extends CacheResultInterceptor {
    @Inject void inject(CacheContextSource<MethodInvocation> cacheContextSource) {
      setCacheContextSource(cacheContextSource);
    }
  }

  private static final class JakartaCacheRemoveEntryInterceptor
      extends CacheRemoveEntryInterceptor {
    @Inject void inject(CacheContextSource<MethodInvocation> cacheContextSource) {
      setCacheContextSource(cacheContextSource);
    }
  }

  private static final class JakartaCacheRemoveAllInterceptor extends CacheRemoveAllInterceptor {
    @Inject void inject(CacheContextSource<MethodInvocation> cacheContextSource) {
      setCacheContextSource(cacheContextSource);
    }
  }

  @Singleton
  private static final class JakartaCacheLookup extends CacheLookupUtil {
    @Inject JakartaCacheLookup(Injector injector, CacheKeyGenerator defaultCacheKeyGenerator,
        CacheResolverFactory defaultCacheResolverFactory) {
      super(injector, defaultCacheKeyGenerator, defaultCacheResolverFactory);
    }
  }
}
