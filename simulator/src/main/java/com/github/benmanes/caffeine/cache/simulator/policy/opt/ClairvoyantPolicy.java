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

import java.util.ArrayDeque;
import java.util.Queue;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntPriorityQueue;
import it.unimi.dsi.fastutil.ints.IntRBTreeSet;
import it.unimi.dsi.fastutil.ints.IntSortedSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

/**
 * Bélády's optimal page replacement policy. The upper bound of the hit rate is estimated
 * by evicting from the cache the item that will next be used farthest into the future.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "opt.Clairvoyant")
public final class ClairvoyantPolicy implements Policy {
  private final Long2ObjectMap<IntPriorityQueue> accessTimes;
  private final PolicyStats policyStats;
  private final IntSortedSet data;
  private final int maximumSize;

  private Recorder recorder;

  private int infiniteTimestamp;
  private int tick;

  public ClairvoyantPolicy(Config config) {
    BasicSettings settings = new BasicSettings(config);
    maximumSize = Ints.checkedCast(settings.maximumSize());
    accessTimes = new Long2ObjectOpenHashMap<>();
    policyStats = new PolicyStats(name());
    infiniteTimestamp = Integer.MAX_VALUE;
    data = new IntRBTreeSet();
  }

  @Override
  public void record(AccessEvent event) {
    if (recorder == null) {
      recorder = event.isPenaltyAware() ? new EventRecorder() : new KeyOnlyRecorder();
    }

    tick++;
    recorder.add(event);
    IntPriorityQueue times = accessTimes.get(event.key());
    if (times == null) {
      times = new IntArrayFIFOQueue();
      accessTimes.put(event.key(), times);
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
    recorder.process();
    policyStats.stopwatch().stop();
  }

  /** Performs the cache operations for the given key. */
  private void process(long key, double hitPenalty, double missPenalty) {
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
      policyStats.recordHitPenalty(hitPenalty);
    } else {
      policyStats.recordMiss();
      policyStats.recordMissPenalty(missPenalty);
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

  /** An optimized strategy for storing the event history. */
  private interface Recorder {
    void add(AccessEvent event);
    void process();
  }

  private final class KeyOnlyRecorder implements Recorder {
    private final LongArrayFIFOQueue future;

    KeyOnlyRecorder() {
      future = new LongArrayFIFOQueue(maximumSize);
    }
    @Override public void add(AccessEvent event) {
      future.enqueue(event.key());
    }
    @Override public void process() {
      while (!future.isEmpty()) {
        ClairvoyantPolicy.this.process(future.dequeueLong(), 0.0, 0.0);
      }
    }
  }

  private final class EventRecorder implements Recorder {
    private final Queue<AccessEvent> future;

    EventRecorder() {
      future = new ArrayDeque<>(maximumSize);
    }
    @Override public void add(AccessEvent event) {
      future.add(event);
    }
    @Override public void process() {
      while (!future.isEmpty()) {
        AccessEvent event = future.poll();
        ClairvoyantPolicy.this.process(event.key(), event.hitPenalty(), event.missPenalty());
      }
    }
  }
}
