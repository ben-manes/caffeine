/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.membership;

/**
 * A probabilistic set for testing the membership of an element.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public interface Membership {

  /**
   * Returns if the element <i>might</i> have been put in this Bloom filter, {@code false} if this
   * is <i>definitely</i> not the case.
   *
   * @param e the element whose presence is to be tested
   * @return if the element might be present
   */
  boolean mightContain(long e);

  /** Removes all of the elements from this collection. */
  void clear();

  /**
   * Puts an element into this collection so that subsequent queries with the same element will
   * return {@code true}.
   *
   * @param e the element to add
   * @return if the membership changed as a result of this operation
   */
  boolean put(long e);

  /** Returns an instance that contains nothing. */
  static Membership disabled() {
    return DisabledMembership.INSTANCE;
  }
}

enum DisabledMembership implements Membership {
  INSTANCE;

  @Override
  public boolean mightContain(long e) {
    return false;
  }

  @Override
  public void clear() {}

  @Override
  public boolean put(long e) {
    return false;
  }
}
