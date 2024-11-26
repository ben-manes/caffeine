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

/**
 * This package contains in-memory caching functionality. All cache variants are configured and
 * created using the {@link com.github.benmanes.caffeine.cache.Caffeine} builder.
 * <p>
 * A {@link com.github.benmanes.caffeine.cache.Cache} provides similar characteristics to
 * {@link java.util.concurrent.ConcurrentHashMap}, with additional support for policies to bound the
 * map. When built with a {@link com.github.benmanes.caffeine.cache.CacheLoader}, the
 * {@link com.github.benmanes.caffeine.cache.LoadingCache} variant allows the cache to populate
 * itself on a miss and offers refresh capabilities.
 * <p>
 * A {@link com.github.benmanes.caffeine.cache.AsyncCache} is similar to a
 * {@link com.github.benmanes.caffeine.cache.Cache}, except that a cache entry holds a
 * {@link java.util.concurrent.CompletableFuture} of the value. This entry will be automatically
 * removed if the future fails, resolves to {@code null}, or based on an eviction policy. When built
 * with a {@link com.github.benmanes.caffeine.cache.AsyncCacheLoader}, the
 * {@link com.github.benmanes.caffeine.cache.AsyncLoadingCache} variant allows the cache to populate
 * itself on a miss and offers refresh capabilities.
 * <p>
 * Additional functionality such as bounding by the entry's size, removal notifications, statistics,
 * and eviction policies are described in the {@link com.github.benmanes.caffeine.cache.Caffeine}
 * builder.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@NullMarked
@CheckReturnValue
package com.github.benmanes.caffeine.cache;

import org.jspecify.annotations.NullMarked;

import com.google.errorprone.annotations.CheckReturnValue;
