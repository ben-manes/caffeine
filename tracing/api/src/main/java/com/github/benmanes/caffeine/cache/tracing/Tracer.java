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

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

/**
 * A tracing api for recording cache operations.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public interface Tracer {

  /**
   * Registers a cache by name for identifying the cache during simulation. The name is not required
   * to be unique and duplicate names will be identified separately by the simulator. The returned
   * identifier is guaranteed to be unique if tracing is enabled.
   *
   * @param name the human readable label for identifying the cache
   * @return a unique identifier of the cache instance
   */
  long register(@Nonnull String name);

  /**
   * Records a read only operation on the cache, which may or may not successfully have returned the
   * associated value. The cache operation must not be of a type that automatically computes the
   * value if the read was unsuccessful (use {@link #recordWrite} instead).
   *
   * @param id the unique identifier of the cache instance
   * @param key key to the retrieved entry
   */
  void recordRead(long id, @Nonnull Object key);

  /**
   * Records a write operation for the insertion or update of an entry in the cache. A write should
   * be recorded for the following operations:
   * <ul>
   *   <li>a read that triggers computing the entry if not present</li>
   *   <li>insertion of a new entry</li>
   *   <li>insertion of a new entry if a mapping for the key is absent</li>
   *   <li>replacement of an existing entry</li>
   * </ul>
   * The cache operation should be recorded regardless of whether the cache was mutated, as
   * replaying the events under simulation will have different effects.
   *
   * @param id the unique identifier of the cache instance
   * @param key key to the entry present in the cache
   * @param weight the weight of the entry
   */
  void recordWrite(long id, @Nonnull Object key, @Nonnegative int weight);

  /**
   * Records the explicit removal of an entry from the cache. The deletion must be recorded
   * regardless of whether the cache was mutated as a result of the operation. A removal caused by
   * eviction should not be recorded, as replaying the events under simulation eviction will occur
   * differently.
   *
   * @param id the unique identifier of the cache instance
   * @param key key to the entry now removed in the cache
   */
  void recordDelete(long id, @Nonnull Object key);

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

  @Override public long register(String name) { return 0L; }
  @Override public void recordRead(long id, Object key) {}
  @Override public void recordWrite(long id, Object key, int weight) {}
  @Override public void recordDelete(long id, Object key) {}
}

final class TracerHolder {
  static final Tracer INSTANCE = load();

  private static Tracer load() {
    String property = System.getProperty("caffeine.tracing.enabled");
    if ((property == null) || Boolean.parseBoolean(property)) {
      for (Tracer tracer : ServiceLoader.load(Tracer.class)) {
        return tracer;
      };
    }
    return DisabledTracer.INSTANCE;
  }
}
