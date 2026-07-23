/*
 * Copyright 2024 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.admission.clairvoyant;

import com.github.benmanes.caffeine.cache.simulator.admission.Admitter.KeyOnlyAdmitter;
import com.github.benmanes.caffeine.cache.simulator.parser.ClairvoyantTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.ClairvoyantTraceReader.Cursor;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * {@literal Bélády's} optimal page replacement policy applied as an admission policy by comparing
 * the keys using their next-access times.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Clairvoyant implements KeyOnlyAdmitter {
  private final Long2LongMap nextAccessTimes;
  private final PolicyStats policyStats;
  private final Cursor cursor;

  public Clairvoyant(Config config, PolicyStats policyStats) {
    this.policyStats = policyStats;
    this.nextAccessTimes = new Long2LongOpenHashMap();
    this.nextAccessTimes.defaultReturnValue(Long.MAX_VALUE);
    this.cursor = ClairvoyantTraceReader.currentCursor().orElseThrow(() -> new IllegalStateException(
        "admission.Clairvoyant requires the clairvoyant trace reader to be installed"));
  }

  @Override
  public void record(long key) {
    long nextAccess = cursor.next();
    if (nextAccess == ClairvoyantTraceReader.NONE) {
      nextAccessTimes.remove(key);
    } else {
      nextAccessTimes.put(key, nextAccess);
    }
  }

  @Override
  public boolean admit(long candidateKey, long victimKey) {
    long candidateTime = nextAccessTimes.get(candidateKey);
    long victimTime = nextAccessTimes.get(victimKey);
    if (candidateTime > victimTime) {
      policyStats.recordRejection();
      return false;
    }
    policyStats.recordAdmission();
    return true;
  }
}
