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
 * This package contains caching utilities.
 * <p>
 * The core interface used to represent caches is {@link com.github.benmanes.caffeine.cache.Cache}.
 * A cache may be specialized as either a {@link com.github.benmanes.caffeine.cache.LoadingCache}
 * or {@link com.github.benmanes.caffeine.cache.AsyncLoadingCache}.
 * <p>
 * In-memory caches can be configured and created using
 * {@link com.github.benmanes.caffeine.cache.Caffeine}. The cache entries may be loaded by
 * {@link com.github.benmanes.caffeine.cache.CacheLoader}, weighed by
 * {@link com.github.benmanes.caffeine.cache.Weigher}, and on removal forwarded to
 * {@link com.github.benmanes.caffeine.cache.RemovalListener}. Statistics about cache performance
 * are exposed using {@link com.github.benmanes.caffeine.cache.stats.CacheStats}.
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
