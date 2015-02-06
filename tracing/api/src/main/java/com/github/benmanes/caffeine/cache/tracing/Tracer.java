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

  void recordCreate(@Nonnull Object o);

  void recordRead(@Nonnull Object o);

  void recordUpdate(@Nonnull Object o);

  void recordDelete(@Nonnull Object o);

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

  @Override public void recordCreate(Object o) {}
  @Override public void recordRead(Object o) {}
  @Override public void recordUpdate(Object o) {}
  @Override public void recordDelete(Object o) {}
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
