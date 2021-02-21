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
 * created by using the {@link Caffeine} builder.
 * <p>
 * A {@link Cache} provides similar characteristics as
 * {@link java.util.concurrent.ConcurrentHashMap} with additional support for policies to bound the
 * map by. When built with a {@link CacheLoader}, the {@link LoadingCache} variant allows the cache
 * to populate itself on miss and offers refresh capabilities.
 * <p>
 * A {@link AsyncCache} is similar to a {@link Cache} except that a cache entry holds a
 * {@link java.util.concurrent.CompletableFuture} of the value. This entry will be automatically
 * removed if the future fails, resolves to {@code null}, or based on an eviction policy. When built
 * with a {@link AsyncCacheLoader}, the {@link AsyncLoadingCache} variant allows the cache to
 * populate itself on miss and offers refresh capabilities.
 * <p>
 * Additional functionality such as bounding by the entry's size, removal notifications, statistics,
 * and eviction policies are described in the {@link Caffeine} builder.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@DefaultQualifier(value = NonNull.class, locations = TypeUseLocation.FIELD)
@DefaultQualifier(value = NonNull.class, locations = TypeUseLocation.PARAMETER)
@DefaultQualifier(value = NonNull.class, locations = TypeUseLocation.RETURN)
package com.github.benmanes.caffeine.cache;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
