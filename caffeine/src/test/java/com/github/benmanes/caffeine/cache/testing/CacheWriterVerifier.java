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
package com.github.benmanes.caffeine.cache.testing;

import static java.util.Objects.requireNonNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.function.BiConsumer;

import org.mockito.Mockito;

import com.github.benmanes.caffeine.cache.CacheWriter;
import com.github.benmanes.caffeine.cache.RemovalCause;

/**
 * A utility for verifying that the {@link CacheWriter} mock was operated on correctly.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheWriterVerifier {
  private final CacheContext context;

  private CacheWriterVerifier(CacheContext context) {
    this.context = requireNonNull(context);
  }

  /** Checks that neither writes nor deletes occurred. */
  public void zeroInteractions() {
    Mockito.verifyNoInteractions(context.cacheWriter());
  }

  /** Checks that the expected number of write operations occurred. */
  public void writes(int count) {
    verify(context.cacheWriter(), times(count)).write(any(), any());
  }

  /** Checks that the key and value were written. */
  public void wrote(Integer key, Integer value) {
    verify(context.cacheWriter()).write(eq(key), eq(value));
  }

  /** Checks that only these entries were written. */
  public void wroteAll(Map<Integer, Integer> map) {
    map.entrySet().forEach(entry -> {
      wrote(entry.getKey(), entry.getValue());
    });
    verify(context.cacheWriter(), times(map.size())).write(any(), any());
  }

  /** Checks that the expected number of delete operations occurred. */
  public void deletions(long count) {
    verify(context.cacheWriter(), times((int) count)).delete(any(), any(), any());
  }

  /** Checks that the expected number of delete operations occurred. */
  public void deletions(long count, RemovalCause cause) {
    verify(context.cacheWriter(), times((int) count)).delete(any(), any(), eq(cause));
  }

  /** Checks that the entry was deleted for the specified reason. */
  public void deleted(Map.Entry<Integer, Integer> entry, RemovalCause cause) {
    deleted(entry.getKey(), entry.getValue(), cause);
  }

  /** Checks that the key and value were deleted for the specified reason. */
  public void deleted(Integer key, Integer value, RemovalCause cause) {
    verify(context.cacheWriter()).delete(eq(key), eq(value), eq(cause));
  }

  /** Checks that only these entries were deleted. */
  public void deletedAll(Map<Integer, Integer> map, RemovalCause cause) {
    map.entrySet().forEach(entry -> deleted(entry, cause));
    deletions(map.size());
  }

  /** Runs the verification block iff the cache writer is enabled. */
  public static void verifyWriter(CacheContext context,
      BiConsumer<CacheWriterVerifier, CacheWriter<Integer, Integer>> consumer) {
    boolean mayVerify = context.isCaffeine()
        && context.isStrongKeys()
        && !context.isAsync();
    if (mayVerify) {
      consumer.accept(new CacheWriterVerifier(context), context.cacheWriter());
    }
  }
}
