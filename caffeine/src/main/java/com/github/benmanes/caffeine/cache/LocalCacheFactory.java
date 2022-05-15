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

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A factory for caches optimized for a particular configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class LocalCacheFactory {
  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static final MethodType FACTORY = MethodType.methodType(
      void.class, Caffeine.class, AsyncCacheLoader.class, boolean.class);

  private LocalCacheFactory() {}

  /** Returns a cache optimized for this configuration. */
  static <K, V> BoundedLocalCache<K, V> newBoundedLocalCache(Caffeine<K, V> builder,
      @Nullable AsyncCacheLoader<? super K, V> cacheLoader, boolean async) {
    var className = getClassName(builder);
    return loadFactory(builder, cacheLoader, async, className);
  }

  static String getClassName(Caffeine<?, ?> builder) {
    var className = new StringBuilder(LocalCacheFactory.class.getPackageName()).append('.');
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

  static <K, V> BoundedLocalCache<K, V> loadFactory(Caffeine<K, V> builder,
      @Nullable AsyncCacheLoader<? super K, V> cacheLoader, boolean async, String className) {
    try {
      Class<?> clazz = Class.forName(className);
      MethodHandle handle = LOOKUP.findConstructor(clazz, FACTORY);
      return (BoundedLocalCache<K, V>) handle.invoke(builder, cacheLoader, async);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(className, t);
    }
  }
}
