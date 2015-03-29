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
package com.github.benmanes.caffeine.cache.buffer;

/**
 * A read buffer strategy. The implementations should prefer failing a one-shot append rather than
 * waiting until the entry has been successfully recorded.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public interface Buffer {
  static final int MAX_SIZE = 32; // power of 2
  static final int MAX_SIZE_MASK = MAX_SIZE - 1;

  /**
   * Attempts to record an event.
   *
   * @return if a drain is needed
   */
  boolean record();

  /** Attempts to drain the events. */
  void drain();

  /** Returns the total number of events recorded. */
  long recorded();

  /** Returns the total number of events consumed. */
  long drained();
}
