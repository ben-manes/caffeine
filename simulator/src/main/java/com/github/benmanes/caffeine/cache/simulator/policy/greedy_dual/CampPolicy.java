/*
 * Copyright 2021 Omri Himelbrand. All Rights Reserved.
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

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.*;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import static com.google.common.base.Preconditions.checkState;

/**
 * CAMP algorithm.
 *
 * @author himelbrand@gmail.com (Omri Himelbrand)
 */
@PolicySpec(name = "greedy-dual.CAMP", characteristics = WEIGHTED)
public final class CampPolicy implements Policy {
    private final Long2ObjectMap<Node> data;
    private final NavigableSet<Sentinel> priorityQueue;
    private final Int2ObjectMap<Sentinel> sentinelMapping;
    private final PolicyStats policyStats;
    private final long maximumSize;
    private final int p;
    private final int bitMask;
    private long requestCount;
    private int size;

    public CampPolicy(Config config) {
        this.priorityQueue = new TreeSet<>();
        var settings = new CampSettings(config);
        this.maximumSize = settings.maximumSize();
        this.policyStats = new PolicyStats(name());
        this.data = new Long2ObjectOpenHashMap<>();
        this.sentinelMapping = new Int2ObjectOpenHashMap<>();
        this.p = settings.p();
        this.bitMask = Integer.MAX_VALUE >> (Integer.SIZE-1-p);
        this.requestCount = 0;
    }

    @Override
    public void record(AccessEvent event) {
        Node node = data.get(event.key());
        requestCount++;
        if (node == null) {
            policyStats.recordWeightedMiss(event.weight());
            onMiss(event);
        } else {
            policyStats.recordWeightedHit(event.weight());
            onHit(node);

            size += (event.weight() - node.weight);
            node.weight = event.weight();
            if (size > maximumSize) {
                evict();
            }
        }
    }

    private void onHit(Node node) {
        Sentinel sentinel = sentinelMapping.get(node.cost);
        node.moveToTail();
        if(priorityQueue.size() > 1) {
            checkState(priorityQueue.remove(sentinel), "cost %s not found in priority queue", sentinel);
            sentinel.priority = priorityQueue.first().priority + sentinel.cost;
            sentinel.lastRequest = requestCount;
            priorityQueue.add(sentinel);
        }
    }

    private int roundedCost(AccessEvent event) {
        int cost = (int)(event.isPenaltyAware() ? event.missPenalty() : 1)/event.weight();
        int msbIndex = (int)(Math.log(Integer.highestOneBit(cost)) / Math.log(2));
        int roundMask = msbIndex < p ? Integer.MAX_VALUE : bitMask<<(msbIndex-p+1);
        return cost&roundMask;
    }

    private void onMiss(AccessEvent event) {
        size += event.weight();
        while(size > maximumSize){
            evict();
        }
        int roundCost = roundedCost(event);
        int priority = priorityQueue.isEmpty() ? roundCost : priorityQueue.first().priority + roundCost;
        Sentinel sentinel = sentinelMapping.get(roundCost);
        if (sentinel == null){
            sentinel = new Sentinel(roundCost);
            sentinel.priority = priority;
            sentinel.lastRequest = requestCount;
            sentinelMapping.put(roundCost,sentinel);
            priorityQueue.add(sentinelMapping.get(roundCost));
        }
        Node node = new Node(event.key(),event.weight(),sentinel);
        node.cost = roundCost;
        sentinel.appendToTail(node);
        data.put(node.key, node);
    }

    private void evict() {
        Sentinel sentinel = priorityQueue.first();
        Node victim = sentinel.next;
        size -= victim.weight;
        victim.remove();
        data.remove(victim.key);
        if (sentinel.isEmpty()){
            sentinelMapping.remove(sentinel.cost);
            priorityQueue.remove(sentinel);
        }
        policyStats.recordEviction();
    }

    @Override
    public PolicyStats stats() {
        return policyStats;
    }



    private static final class Sentinel extends Node implements Comparable<Sentinel>{
        int priority;
        long lastRequest;

        public Sentinel(int cost) {
            super(Long.MIN_VALUE);
            this.cost = cost;
            prev = next = this;
        }

        /** Returns if the queue is empty. */
        public boolean isEmpty() {
            return (next == this);
        }

        /** Appends the node to the tail of the list. */
        public void appendToTail(Node node) {
            Node tail = prev;
            prev = node;
            tail.next = node;
            node.next = this;
            node.prev = tail;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("cost", cost)
                    .add("priority", priority)
                    .toString();
        }

        @Override
        public int compareTo(Sentinel s) {
            if (priority != s.priority)
                return priority - s.priority;
            return (int) Math.signum(s.lastRequest - lastRequest);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Sentinel sentinel = (Sentinel) o;
            return cost == sentinel.cost && priority == sentinel.priority;
        }

        @Override
        public int hashCode() {
            return Objects.hash(cost, priority);
        }
    }

    private static class Node {
        final long key;
        int weight;
        int cost;

        Node prev;
        Node next;
        Sentinel sentinel;

        public Node(long key) {
            this.key = key;
        }

        public Node(long key,int weight, Sentinel sentinel) {
            this.key = key;
            this.weight = weight;
            this.sentinel = sentinel;
        }

        /** Removes the node from the list. */
        public void remove() {
            checkState(!(this instanceof Sentinel));
            prev.next = next;
            next.prev = prev;
            prev = next = null;
        }

        /** Moves the node to the tail. */
        public void moveToTail() {
            // unlink
            prev.next = next;
            next.prev = prev;

            // link
            next = sentinel;
            prev = sentinel.prev;
            sentinel.prev = this;
            prev.next = this;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("key", key)
                    .add("weight", weight)
                    .toString();
        }
    }

    private static final class CampSettings extends BasicSettings {
        public CampSettings(Config config) {
            super(config);
        }
        public int p() {
            return config().getInt("camp.p");
        }
    }
}
