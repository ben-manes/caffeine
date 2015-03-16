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
package com.github.benmanes.caffeine.cache.simulator.policy.sampled;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import scala.concurrent.forkjoin.ThreadLocalRandom;
import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.dispatch.BoundedMessageQueueSemantics;
import akka.dispatch.RequiresMessageQueue;

import com.github.benmanes.caffeine.cache.simulator.Simulator.Message;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.tracing.TraceEvent;
import com.google.common.base.MoreObjects;

/**
 * A skeletal implementation of a caching policy implemented a sampled array of entries.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
abstract class AbstractSamplingPolicy extends UntypedActor
    implements RequiresMessageQueue<BoundedMessageQueueSemantics> {
  private final Map<Integer, Node> data;
  private final PolicyStats policyStats;
  private final EvictionPolicy policy;
  private final Sample sampleStrategy;
  private final Deque<Integer> free;
  private final int sampleSize;
  private final Node[] table;

  /**
   * Creates an actor that delegates to an LRU, FIFO, or RANDOM based cache.
   *
   * @param name the name of this policy
   * @param policy the eviction policy to apply
   */
  protected AbstractSamplingPolicy(String name, EvictionPolicy policy) {
    SamplingSettings settings = new SamplingSettings(this);
    this.sampleStrategy = settings.sampleStrategy();
    this.policyStats = new PolicyStats(name);
    this.sampleSize = settings.sampleSize();
    this.data = new HashMap<>();
    this.policy = policy;

    this.free = new ArrayDeque<Integer>(settings.maximumSize());
    this.table = new Node[settings.maximumSize()];
    for (int i = 0; i < settings.maximumSize(); i++) {
      free.add(i);
    }
  }

  @Override
  public void onReceive(Object msg) throws Exception {
    if (msg instanceof TraceEvent) {
      policyStats.stopwatch().start();
      handleEvent((TraceEvent) msg);
      policyStats.stopwatch().stop();
    } else if (msg == Message.END) {
      getSender().tell(policyStats, ActorRef.noSender());
      getContext().stop(getSelf());
    }
  }

  private void handleEvent(TraceEvent event) {
    switch (event.action()) {
      case WRITE:
        if (data.containsKey(event.keyHash())) {
          onRead(event);
        } else {
          onCreateOrUpdate(event);
        }
        break;
      case READ:
        onRead(event);
        break;
      case DELETE:
        onDelete(event);
        break;
      default:
        throw new UnsupportedOperationException();
    }
  }

  private void onCreateOrUpdate(TraceEvent event) {
    Node node = data.get(event.keyHash());
    if (node == null) {
      evict();
      node = new Node(event.keyHash(), free.pop());
      data.put(event.keyHash(), node);
      table[node.index] = node;
    } else {
      node.accessTime = System.nanoTime();
    }
  }

  private void onRead(TraceEvent event) {
    Node node = data.get(event.keyHash());
    if (node == null) {
      policyStats.recordMiss();
    } else {
      node.accessTime = System.nanoTime();
      policyStats.recordHit();
      node.frequency++;
    }
  }

  private void onDelete(TraceEvent event) {
    Node node = data.remove(event.keyHash());
    if (node != null) {
      removeFromTable(node);
    }
  }

  /** Evicts while the map exceeds the maximum capacity. */
  private void evict() {
    if (free.isEmpty()) {
      List<Node> sample = (policy == EvictionPolicy.RANDOM)
          ? Arrays.asList(table)
          : sampleStrategy.sample(table, sampleSize);
      Node victim = policy.select(sample);
      policyStats.recordEviction();
      removeFromTable(victim);
      data.remove(victim.key);
    }
  }

  /** Removes the node from the table and adds the index to the free list. */
  private void removeFromTable(Node node) {
    int last = (table.length - free.size()) - 1;
    table[node.index] = table[last];
    table[node.index].index = node.index;
    table[last] = null;
    free.push(last);
  }

  /** The algorithms to choose a random sample with. */
  public enum Sample {
    GUESS {
      @Override public <E> List<E> sample(E[] elements, int sampleSize) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Set<Integer> sampled = new HashSet<>(sampleSize);
        List<E> sample = new ArrayList<E>(sampleSize);
        while (sampled.size() != sampleSize) {
          int index = random.nextInt(elements.length);
          if (sampled.add(index)) {
            sample.add(elements[index]);
          }
        }
        return sample;
      }
    },
    RESERVOIR {
      @Override public <E> List<E> sample(E[] elements, int sampleSize) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<E> sample = new ArrayList<>(sampleSize);
        int count = 0;
        for (E e : elements) {
          count++;
          if (sample.size() <= sampleSize) {
            sample.add(e);
          } else {
            int index = random.nextInt(count);
            if (index < sampleSize) {
              sample.set(index, e);
            }
          }
        }
        return sample;
      }
    },
    SHUFFLE {
      @Override public <E> List<E> sample(E[] elements, int sampleSize) {
        List<E> sample = new ArrayList<>(Arrays.asList(elements));
        Collections.shuffle(sample, ThreadLocalRandom.current());
        return sample.subList(0, sampleSize);
      }
    };

    abstract <E> List<E> sample(E[] elements, int sampleSize);
  }

  /** The replacement policy. */
  protected enum EvictionPolicy {

    /** Evicts entries based on insertion order. */
    FIFO() {
      @Override Node select(List<Node> sample) {
        return sample.stream().min((first, second) ->
            Long.compare(first.insertionTime, second.insertionTime)).get();
      }
    },

    /** Evicts entries based on how recently they are used, with the least recent evicted first. */
    LRU() {
      @Override Node select(List<Node> sample) {
        return sample.stream().min((first, second) ->
            Long.compare(first.accessTime, second.accessTime)).get();
      }
    },

    /** Evicts entries based on how recently they are used, with the least recent evicted first. */
    MRU() {
      @Override Node select(List<Node> sample) {
        return sample.stream().max((first, second) ->
            Long.compare(first.accessTime, second.accessTime)).get();
      }
    },

    /**
     * Evicts entries based on how frequently they are used, with the least frequent evicted first.
     */
    LFU() {
      @Override Node select(List<Node> sample) {
        return sample.stream().min((first, second) ->
            Long.compare(first.frequency, second.frequency)).get();
      }
    },

    /**
     * Evicts entries based on how frequently they are used, with the most frequent evicted first.
     */
    MFU() {
      @Override Node select(List<Node> sample) {
        return sample.stream().max((first, second) ->
            Long.compare(first.frequency, second.frequency)).get();
      }
    },

    /** Evicts a random entry. */
    RANDOM() {
      @Override Node select(List<Node> sample) {
        int victim = ThreadLocalRandom.current().nextInt(sample.size());
        return sample.get(victim);
      }
    };

    /** Determines which node to evict. */
    abstract Node select(List<Node> sample);
  }

  /** A node on the double-linked list. */
  static final class Node {
    private final Integer key;

    private long insertionTime;
    private long accessTime;
    private int frequency;
    private int index;

    /** Creates a new node. */
    public Node(Integer key, int index) {
      this.insertionTime = System.nanoTime();
      this.accessTime = insertionTime;
      this.index = index;
      this.key = key;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("index", index)
          .toString();
    }
  }
}
