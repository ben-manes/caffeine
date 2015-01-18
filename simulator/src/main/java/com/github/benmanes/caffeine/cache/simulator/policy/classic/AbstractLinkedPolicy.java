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
package com.github.benmanes.caffeine.cache.simulator.policy.classic;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.dispatch.BoundedMessageQueueSemantics;
import akka.dispatch.RequiresMessageQueue;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.Simulator.Message;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.tracing.CacheEvent;
import com.github.benmanes.caffeine.cache.tracing.CacheEvent.Action;

/**
 * A skeletal implementation of a caching policy implemented a linked list maintained in either
 * insertion or access order.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
abstract class AbstractLinkedPolicy extends UntypedActor
    implements RequiresMessageQueue<BoundedMessageQueueSemantics> {
  private static final Object VALUE = new Object();

  private final BoundedLinkedHashMap cache;
  private final PolicyStats policyStats;

  /**
   * Creates an actor that delegates to an LRU or FIFO based cache.
   *
   * @param name the name of this policy
   * @param accessOrder the ordering mode
   */
  protected AbstractLinkedPolicy(String name, boolean accessOrder) {
    this.policyStats = new PolicyStats(name);
    BasicSettings settings = new BasicSettings(this);
    this.cache = new BoundedLinkedHashMap(accessOrder, settings.maximumSize());
  }

  @Override
  public void onReceive(Object msg) throws Exception {
    if (msg instanceof CacheEvent) {
      handleEvent((CacheEvent) msg);
    } else if (msg == Message.DONE) {
      getSender().tell(policyStats, ActorRef.noSender());
      getContext().stop(getSelf());
    }
  }

  private void handleEvent(CacheEvent event) {
    if (event.action() == Action.CREATE) {
      cache.put(event.hash(), VALUE);
    } else if (event.action() == Action.READ) {
      recordHitOrMiss(cache.get(event.hash()) != null);
    } else if (event.action() == Action.UPDATE) {
      cache.replace(event.hash(), VALUE);
    } else if (event.action() == Action.UPDATE) {
      cache.remove(event.hash());
    } else if (event.action() == Action.READ_OR_CREATE) {
      recordHitOrMiss(cache.putIfAbsent(event.hash(), VALUE));
    } else {
      throw new UnsupportedOperationException();
    }
  }

  private void recordHitOrMiss(@Nullable Object o) {
    if (o == null) {
      policyStats.recordMiss();
    } else {
      policyStats.recordHit();
    }
  }

  final class BoundedLinkedHashMap extends LinkedHashMap<Integer, Object> {
    private static final long serialVersionUID = 1L;

    private final int maximumSize;

    private BoundedLinkedHashMap(boolean accessOrder, int maximumSize) {
      super(maximumSize, 0.75f, accessOrder);
      this.maximumSize = maximumSize;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<Integer, Object> eldest) {
      boolean evict = size() > maximumSize;
      if (evict) {
        policyStats.recordEviction();
      }
      return evict;
    }
  }
}
