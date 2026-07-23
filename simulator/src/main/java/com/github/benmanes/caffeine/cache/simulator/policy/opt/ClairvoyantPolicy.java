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
package com.github.benmanes.caffeine.cache.simulator.policy.opt;

import static com.github.benmanes.caffeine.cache.simulator.parser.ClairvoyantTraceReader.NONE;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.parser.ClairvoyantTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.ClairvoyantTraceReader.Cursor;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.LongRBTreeSet;
import it.unimi.dsi.fastutil.longs.LongSortedSet;

/**
 * {@literal Bélády's} optimal page replacement policy. The upper bound of the hit rate is estimated
 * by evicting from the cache the item that will next be used farthest into the future.
 * <p>
 * The next-access time of each request is supplied by the shared {@link ClairvoyantTraceReader},
 * which precomputes it to a temporary file so only the resident set is held in memory rather than
 * the whole trace. Residents are keyed by their next-access time, so the farthest-future victim is
 * the last entry of a sorted set; a request whose key never recurs is keyed by a decreasing
 * sentinel so it sorts beyond every real time.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "opt.Clairvoyant")
public final class ClairvoyantPolicy implements Policy {
  private final PolicyStats policyStats;
  private final LongSortedSet data;
  private final Cursor cursor;
  private final int maximumSize;

  private long infiniteTimestamp;
  private long tick;

  public ClairvoyantPolicy(Config config) {
    var settings = new BasicSettings(config);
    cursor = ClairvoyantTraceReader.currentCursor().orElseThrow(() -> new IllegalStateException(
        "opt.Clairvoyant requires the clairvoyant trace reader to be installed"));
    maximumSize = Math.toIntExact(settings.maximumSize());
    policyStats = new PolicyStats(name());
    infiniteTimestamp = Long.MAX_VALUE;
    data = new LongRBTreeSet();
    tick = -1;
  }

  @Override
  public void record(AccessEvent event) {
    tick++;
    long nextAccess = cursor.next();
    boolean found = data.remove(tick);
    data.add((nextAccess == NONE) ? infiniteTimestamp-- : nextAccess);
    if (found) {
      policyStats.recordHit();
    } else {
      policyStats.recordMiss();
      if (data.size() > maximumSize) {
        data.remove(data.lastLong());
        policyStats.recordEviction();
      }
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void finished() {
    cursor.close();
  }
}
