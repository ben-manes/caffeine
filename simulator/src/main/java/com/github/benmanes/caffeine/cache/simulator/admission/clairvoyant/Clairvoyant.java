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

import static com.google.common.base.Preconditions.checkState;

import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.apache.commons.lang3.mutable.MutableInt;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor.KeyOnlyAdmittor;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.errorprone.annotations.Var;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntPriorityQueue;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * {@literal Bélády's} optimal page replacement policy applied as an admission policy by comparing
 * the keys using their next access times.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Clairvoyant implements KeyOnlyAdmittor {
  private static final AtomicReference<Long2ObjectMap<IntList>> snapshot = new AtomicReference<>();

  private final Long2ObjectMap<IntPriorityQueue> accessTimes;
  private final PolicyStats policyStats;

  public Clairvoyant(Config config, PolicyStats policyStats) {
    @Var var readOnlyAccessTimes = snapshot.get();
    if (readOnlyAccessTimes == null) {
      readOnlyAccessTimes = readAccessTimes(new BasicSettings(config));
      snapshot.set(readOnlyAccessTimes);
    }
    accessTimes = new Long2ObjectOpenHashMap<>(readOnlyAccessTimes.size());
    for (var entry : readOnlyAccessTimes.long2ObjectEntrySet()) {
      var times = new IntArrayFIFOQueue(entry.getValue().size());
      accessTimes.put(entry.getLongKey(), times);
      entry.getValue().forEach(times::enqueue);
    }
    this.policyStats = policyStats;
  }

  @Override
  public void record(long key) {
    if (snapshot.get() != null) {
      snapshot.set(null);
    }

    var times = accessTimes.get(key);
    if (times == null) {
      return;
    }
    times.dequeueInt();
    if (times.isEmpty()) {
      accessTimes.remove(key);
    }
  }

  @Override
  public boolean admit(long candidateKey, long victimKey) {
    var candidateTime = nextAccessTime(candidateKey);
    var victimTime = nextAccessTime(victimKey);
    if (candidateTime > victimTime) {
      policyStats.recordRejection();
      return false;
    }
    policyStats.recordAdmission();
    return true;
  }

  private int nextAccessTime(long key) {
    var times = accessTimes.get(key);
    return ((times == null) || times.isEmpty()) ? Integer.MAX_VALUE : times.firstInt();
  }

  private static Long2ObjectMap<IntList> readAccessTimes(BasicSettings settings) {
    checkState(!settings.trace().isSynthetic(), "Synthetic traces cannot be predicted");
    var accessTimes = new Long2ObjectOpenHashMap<IntList>();
    var trace = settings.trace().traceFiles().format()
        .readFiles(settings.trace().traceFiles().paths());
    try (Stream<AccessEvent> events = trace.events()) {
      var tick = new MutableInt();
      events.forEach(event -> {
        var times = accessTimes.computeIfAbsent(event.key(), _ -> new IntArrayList());
        times.add(tick.incrementAndGet());
      });
    }
    return Long2ObjectMaps.unmodifiable(accessTimes);
  }
}
