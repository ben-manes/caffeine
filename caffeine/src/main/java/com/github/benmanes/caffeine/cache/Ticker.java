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

/**
 * A time source that returns a time value representing the number of nanoseconds elapsed since some
 * fixed but arbitrary point in time.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@FunctionalInterface
public interface Ticker {

  /**
   * Returns the number of nanoseconds elapsed since this ticker's fixed point of reference.
   *
   * @return the number of nanoseconds elapsed since this ticker's fixed point of reference
   */
  long read();

  /**
   * Returns a ticker that reads the current time using {@link System#nanoTime}.
   *
   * @return a ticker that reads the current time using {@link System#nanoTime}
   */
  static Ticker systemTicker() {
    return SystemTicker.INSTANCE;
  }

  /**
   * Returns a ticker that always returns {@code 0}.
   *
   * @return a ticker that always returns {@code 0}
   */
  static Ticker disabledTicker() {
    return DisabledTicker.INSTANCE;
  }
}

enum SystemTicker implements Ticker {
  INSTANCE;

  @Override public long read() {
    return System.nanoTime();
  }
}

enum DisabledTicker implements Ticker {
  INSTANCE;

  @Override public long read() {
    return 0L;
  }
}
