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
package com.github.benmanes.caffeine.cache;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jspecify.annotations.Nullable;

import com.google.errorprone.annotations.Var;

/**
 * A factory for caches optimized for a particular configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
interface LocalCacheFactory {
  MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  MethodType FACTORY = MethodType.methodType(
      void.class, Caffeine.class, AsyncCacheLoader.class, boolean.class);
  MethodType FACTORY_CALL = FACTORY.changeReturnType(BoundedLocalCache.class);
  ConcurrentMap<String, LocalCacheFactory> FACTORIES = new ConcurrentHashMap<>();

  /** Returns a cache optimized for this configuration. */
  <K, V> BoundedLocalCache<K, V> newInstance(Caffeine<K, V> builder,
      @Nullable AsyncCacheLoader<? super K, V> cacheLoader, boolean isAsync) throws Throwable;

  /** Returns a cache optimized for this configuration. */
  static <K, V> BoundedLocalCache<K, V> newBoundedLocalCache(Caffeine<K, V> builder,
      @Nullable AsyncCacheLoader<? super K, V> cacheLoader, boolean isAsync) {
    var className = getClassName(builder);
    var factory = loadFactory(className);
    try {
      return factory.newInstance(builder, cacheLoader, isAsync);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(className, t);
    }
  }

  static String getClassName(Caffeine<?, ?> builder) {
    var className = new StringBuilder();
    if (builder.isStrongKeys()) {
      className.append('S');
    } else {
      className.append('W');
    }
    if (builder.isStrongValues()) {
      className.append('S');
    } else {
      className.append('I');
    }
    if (builder.removalListener != null) {
      className.append('L');
    }
    if (builder.isRecordingStats()) {
      className.append('S');
    }
    if (builder.evicts()) {
      className.append('M');
      if (builder.isWeighted()) {
        className.append('W');
      } else {
        className.append('S');
      }
    }
    if (builder.expiresAfterAccess() || builder.expiresVariable()) {
      className.append('A');
    }
    if (builder.expiresAfterWrite()) {
      className.append('W');
    }
    if (builder.refreshAfterWrite()) {
      className.append('R');
    }
    return className.toString();
  }

  static LocalCacheFactory loadFactory(String className) {
    @Var var factory = FACTORIES.get(className);
    if (factory == null) {
      factory = FACTORIES.computeIfAbsent(className, LocalCacheFactory::newFactory);
    }
    return factory;
  }

  static LocalCacheFactory newFactory(String className) {
    try {
      var clazz = LOOKUP.findClass(LocalCacheFactory.class.getPackageName() + "." + className);
      try {
        // Fast path
        return (LocalCacheFactory) LOOKUP
            .findStaticVarHandle(clazz, "FACTORY", LocalCacheFactory.class).get();
      } catch (NoSuchFieldException e) {
        // Slow path when native hints are missing the field, but may have the constructor
        return new MethodHandleBasedFactory(clazz);
      }
    } catch (ClassNotFoundException | IllegalAccessException | NoSuchMethodException t) {
      throw new IllegalStateException(className, t);
    }
  }

  final class MethodHandleBasedFactory implements LocalCacheFactory {
    final MethodHandle methodHandle;

    MethodHandleBasedFactory(Class<?> clazz) throws NoSuchMethodException, IllegalAccessException {
      this.methodHandle = LOOKUP.findConstructor(clazz, FACTORY).asType(FACTORY_CALL);
    }
    @SuppressWarnings({"ClassEscapesDefinedScope", "unchecked"})
    @Override public <K, V> BoundedLocalCache<K, V> newInstance(Caffeine<K, V> builder,
        @Nullable AsyncCacheLoader<? super K, V> cacheLoader, boolean async) throws Throwable {
      return (BoundedLocalCache<K, V>) methodHandle.invokeExact(builder, cacheLoader, async);
    }
  }
}
