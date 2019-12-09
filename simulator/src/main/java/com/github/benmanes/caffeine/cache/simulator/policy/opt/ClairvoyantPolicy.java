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

import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.collect.ImmutableSet;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntPriorityQueue;
import it.unimi.dsi.fastutil.ints.IntRBTreeSet;
import it.unimi.dsi.fastutil.ints.IntSortedSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

/**
 * <pre>Bélády's</pre> optimal page replacement policy. The upper bound of the hit rate is estimated
 * by evicting from the cache the item that will next be used farthest into the future.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ClairvoyantPolicy implements KeyOnlyPolicy {
  private final Long2ObjectMap<IntPriorityQueue> accessTimes;
  private final LongArrayFIFOQueue future;
  private final PolicyStats policyStats;
  private final IntSortedSet data;
  private final int maximumSize;

  private int infiniteTimestamp;
  private int tick;

  public ClairvoyantPolicy(Config config) {
    BasicSettings settings = new BasicSettings(config);
    policyStats = new PolicyStats("opt.Clairvoyant");
    accessTimes = new Long2ObjectOpenHashMap<>();
    infiniteTimestamp = Integer.MAX_VALUE;
    maximumSize = settings.maximumSize();
    future = new LongArrayFIFOQueue();
    data = new IntRBTreeSet();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    return ImmutableSet.of(new ClairvoyantPolicy(config));
  }

  @Override
  public void record(long key) {
    tick++;
    future.enqueue(key);
    IntPriorityQueue times = accessTimes.get(key);
    if (times == null) {
      times = new IntArrayFIFOQueue();
      accessTimes.put(key, times);
    }
    times.enqueue(tick);
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void finished() {
    policyStats.stopwatch().start();
    while (!future.isEmpty()) {
      process(future.dequeueLong());
    }
    policyStats.stopwatch().stop();
  }

  /** Performs the cache operations for the given key. */
  private void process(long key) {
    IntPriorityQueue times = accessTimes.get(key);

    int lastAccess = times.dequeueInt();
    boolean found = data.remove(lastAccess);

    if (times.isEmpty()) {
      data.add(infiniteTimestamp--);
      accessTimes.remove(key);
    } else {
      data.add(times.firstInt());
    }
    if (found) {
      policyStats.recordHit();
    } else {
      policyStats.recordMiss();
      if (data.size() > maximumSize) {
        evict();
      }
    }
  }

  /** Removes the entry whose next access is farthest away into the future. */
  private void evict() {
    data.remove(data.lastInt());
    policyStats.recordEviction();
  }
}
