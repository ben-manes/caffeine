/*
 * Copyright 2021 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.policy.greedy_dual;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import static com.google.common.base.Preconditions.checkState;

import java.util.LinkedHashSet;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Greedy Dual Size Frequency (GDSF) algorithm.
 * <p>
 * The algorithm is explained by the authors in
 * <a href="http://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.304.6514&rep=rep1&type=pdf">
 * Improving Web Servers and Proxies Performance with GDSF Caching Policies</a> and
 * <a href="http://web.cs.iastate.edu/~ciardo/pubs/2001HPCN-GreedyDualFreqSize.pdf">Role of Aging,
 * Frequency, and Size in Web Cache Replacement Policies</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "greedy-dual.Gdsf", characteristics = WEIGHTED)
public final class GdsfPolicy implements Policy {
  private final NavigableSet<Node> priorityQueue;
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final long maximumSize;

  double clock;
  long size;

  public GdsfPolicy(Config config) {
    var settings = new BasicSettings(config);
    policyStats = new PolicyStats(name());
    data = new Long2ObjectOpenHashMap<>();
    maximumSize = settings.maximumSize();
    priorityQueue = new TreeSet<>();
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void record(AccessEvent event) {
    Node node = data.get(event.key());
    if (node == null) {
      policyStats.recordWeightedMiss(event.weight());
      onMiss(event);
    } else {
      policyStats.recordWeightedHit(event.weight());
      onHit(event, node);

      size += (event.weight() - node.weight);
      node.weight = event.weight();
      if (size > maximumSize) {
        evict(node);
      }
    }
  }

  private void onHit(AccessEvent event, Node node) {
    // If the request for f is a hit, f is served out of cache and:
    //  – Used and Clock do not change
    //  – Fr(f) is increased by one
    //  – Pr(f) is updated using Eq. 1 and f is moved accordingly in the queue
    checkState(priorityQueue.remove(node), "%s not found in priority queue", node.key);
    node.frequency++;
    node.priority = priorityOf(event, node.frequency);
    priorityQueue.add(node);
  }

  private double priorityOf(AccessEvent event, int frequency) {
    // As defined in "Improving Web Servers and Proxies Performance with GDSF Caching Policies"
    double cost = event.isPenaltyAware() ? event.missPenalty() : 1.0;
    return clock + frequency * (cost / event.weight());
  }

  private void onMiss(AccessEvent event) {
    // If the request for f is a miss, we need to decide whether to cache f or not:
    //  – Fr(f) is set to one.
    //  – Pr(f) is computed using Eq. 1 and f is enqueued accordingly.
    //  – Used is increased by Size(f).
    Node candidate = new Node(event.key(), event.weight(), priorityOf(event, 1));
    data.put(candidate.key, candidate);
    priorityQueue.add(candidate);
    size += candidate.weight;

    if (size <= maximumSize) {
      policyStats.recordAdmission();
    } else {
      evict(candidate);
    }
  }

  private void evict(Node candidate) {
    // If Used > Total, not all files fit in the cache. First, we identify the smallest set
    // {f1, f2, ... fk} of files to evict, which have the lowest priority and satisfy
    // (Used − sum(f1 ... fk)) <= Total
    var victims = getVictims(size - maximumSize);

    if (victims.contains(candidate)) {
      // If f is among {f1, f2, ... fk}, it is simply not cached and removed from the priority
      // queue, while none of the files already in the cache is evicted. This happens when the value
      // of Pr(f) is so low that it would put f (if cached) among the first candidates for
      // replacement, e.g. when the file size is very large. Thus the proposed procedure will
      // automatically limit the cases when such files are cached
      policyStats.recordRejection();
      remove(candidate);
    } else {
      // If f is not among {f1, f2, ... fk}:
      //  - Clock is set to max priority(f1 ... fk)
      //  - Used is decreased by sum(f1 ... fk)
      //  - f1 ... fk are evicted
      //  - f is cached
      policyStats.recordAdmission();
      for (var victim : victims) {
        if (victim.priority > clock) {
          clock = victim.priority;
        }
        remove(victim);
      }
    }
    checkState(size <= maximumSize);
  }

  private Set<Node> getVictims(long weightDifference) {
    long weightedSize = 0L;
    var victims = new LinkedHashSet<Node>();
    for (Node node : priorityQueue) {
      victims.add(node);
      weightedSize += node.weight;
      if (weightedSize >= weightDifference) {
        break;
      }
    }
    return victims;
  }

  private void remove(Node node) {
    policyStats.recordEviction();
    priorityQueue.remove(node);
    data.remove(node.key);
    size -= node.weight;
  }

  @Override
  public void finished() {
    checkState(size <= maximumSize, "%s > %s", size, maximumSize);

    long weightedSize = data.values().stream().mapToLong(node -> node.weight).sum();
    checkState(weightedSize == size, "%s != %s", weightedSize, size);

    checkState(priorityQueue.size() == data.size(), "%s != %s", priorityQueue.size(), data.size());
    checkState(priorityQueue.containsAll(data.values()), "Data != PriorityQueue");
  }

  private static final class Node implements Comparable<Node> {
    final long key;

    double priority;
    int frequency;
    int weight;

    public Node(long key, int weight, double priority) {
      this.priority = priority;
      this.weight = weight;
      this.frequency = 1;
      this.key = key;
    }

    @Override
    public int compareTo(Node node) {
      int result = Double.compare(priority, node.priority);
      return (result == 0) ? Long.compare(key, node.key) : result;
    }

    @Override
    public boolean equals(Object o) {
      if (o == this) {
        return true;
      } else if (!(o instanceof Node)) {
        return false;
      }
      var node = (Node) o;
      return (key == node.key) && (priority == node.priority);
    }

    @Override
    public int hashCode() {
      return Long.hashCode(key);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(Node.class)
          .add("key", key)
          .add("weight", weight)
          .add("priority", priority)
          .add("frequency", frequency)
          .toString();
    }
  }
}
