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
package com.github.benmanes.caffeine.cache.simulator.admission;

/**
 * An admission policy to the cache. A page replacement policy always admits new entries and chooses
 * a victim to remove if the cache exceeds a maximum size. An admission policy augments the eviction
 * policy by letting the cache not accept the new entry, based on the assumption that the victim is
 * more likely to be used again.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public interface Admittor {

  /** Records the access to the entry. */
  void record(long key);

  /**
   * Returns if the candidate should be added to the cache and the page replacement policy's chosen
   * victim should be removed.
   *
   * @param candidateKey the key to the newly added entry
   * @param victimKey the key to the entry the policy recommends removing
   * @return if the candidate should be added and the victim removed due to eviction
   */
  boolean admit(long candidateKey, long victimKey);

  /** Returns an admittor that admits every candidate. */
  static Admittor always() {
    return AlwaysAdmit.INSTANCE;
  }
}

enum AlwaysAdmit implements Admittor {
  INSTANCE;

  @Override
  public void record(long key) {}

  @Override
  public boolean admit(long candidateKey, long victimKey) {
    return true;
  }
}
