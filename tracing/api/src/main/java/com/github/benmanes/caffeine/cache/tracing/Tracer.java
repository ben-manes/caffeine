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
package com.github.benmanes.caffeine.cache.tracing;

import java.util.ServiceLoader;

import javax.annotation.Nonnull;

/**
 * A tracing api for recording cache operations (create-read-update-delete).
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public interface Tracer {

  /**
   * Records the addition of a new entry to the cache.
   *
   * @param key key to the entry now present in the cache
   * @param weight the weight of the entry
   */
  void recordCreate(@Nonnull Object key, int weight);

  /**
   * Records the entry that was read from the cache.
   *
   * @param key key to the retrieved entry
   */
  void recordRead(@Nonnull Object key);

  /**
   * Records the entry that was updated in the cache.
   *
   * @param key key to the entry updated in the cache
   * @param weightDifference the difference between the old and new entry's weight
   */
  void recordUpdate(@Nonnull Object key, int weightDifference);

  /**
   * Records the removal of an entry from the cache.
   *
   * @param key key to the entry now removed in the cache
   */
  void recordDelete(@Nonnull Object key);

  /** @return if tracing is enabled and an implementation has been loaded. */
  public static boolean isEnabled() {
    return getDefault() != disabled();
  }

  /** @return a tracer implementation that does not record any events. */
  public static Tracer disabled() {
    return DisabledTracer.INSTANCE;
  }

  /**
   * Returns the tracer implementation loaded from a {@link ServiceLoader} or a disabled instance
   * if either not found or the system property <tt>caffeine.tracing.enabled</tt> is set to
   * <tt>false</tt>.
   *
   * @return the tracer implementation that was loaded or a disabled instance otherwise
   */
  public static Tracer getDefault() {
    return TracerHolder.INSTANCE;
  }
}

enum DisabledTracer implements Tracer {
  INSTANCE;

  @Override public void recordCreate(Object key, int weight) {}
  @Override public void recordRead(Object key) {}
  @Override public void recordUpdate(Object key, int weightDifference) {}
  @Override public void recordDelete(Object key) {}
}

final class TracerHolder {
  static final Tracer INSTANCE = load();

  private static Tracer load() {
    String property = System.getProperty("caffeine.tracing.enabled");
    if ((property == null) || Boolean.valueOf(property)) {
      for (Tracer tracer : ServiceLoader.load(Tracer.class)) {
        return tracer;
      };
    }
    return DisabledTracer.INSTANCE;
  }
}
