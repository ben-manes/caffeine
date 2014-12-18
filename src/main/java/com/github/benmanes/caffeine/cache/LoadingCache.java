/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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

import java.util.concurrent.ExecutionException;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.UncheckedExecutionException;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public interface LoadingCache<K, V> extends Cache<K, V> {

  /**
   * @throws ExecutionException if a checked exception was thrown while loading the value. ({@code
   *     ExecutionException} is thrown <a
   *     href="http://code.google.com/p/guava-libraries/wiki/CachesExplained#Interruption">even if
   *     computation was interrupted by an {@code InterruptedException}</a>.)
   * @throws UncheckedExecutionException if an unchecked exception was thrown while loading the
   *     value
   * @throws ExecutionError if an error was thrown while loading the value
   */
  V get(K key) throws ExecutionException;

  /**
   * @throws UncheckedExecutionException if an exception was thrown while loading the value. (As
   *     explained in the last paragraph above, this should be an unchecked exception only.)
   * @throws ExecutionError if an error was thrown while loading the value
   */
  V getUnchecked(K key);

  /**
   * @throws ExecutionException if a checked exception was thrown while loading the value. ({@code
   *     ExecutionException} is thrown <a
   *     href="http://code.google.com/p/guava-libraries/wiki/CachesExplained#Interruption">even if
   *     computation was interrupted by an {@code InterruptedException}</a>.)
   * @throws UncheckedExecutionException if an unchecked exception was thrown while loading the
   *     values
   * @throws ExecutionError if an error was thrown while loading the values
   * @since 11.0
   */
  ImmutableMap<K, V> getAll(Iterable<? extends K> keys) throws ExecutionException;

  /* @Deprecated V apply(K key); */

  void refresh(K key);
}
