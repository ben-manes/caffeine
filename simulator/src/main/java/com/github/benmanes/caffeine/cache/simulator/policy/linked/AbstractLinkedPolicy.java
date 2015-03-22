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
package com.github.benmanes.caffeine.cache.simulator.policy.linked;

import java.util.HashMap;
import java.util.Map;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.dispatch.BoundedMessageQueueSemantics;
import akka.dispatch.RequiresMessageQueue;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.Simulator.Message;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.tracing.TraceEvent;
import com.google.common.base.MoreObjects;

/**
 * A skeletal implementation of a caching policy implemented a linked list maintained in either
 * insertion or access order.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
abstract class AbstractLinkedPolicy extends UntypedActor
    implements RequiresMessageQueue<BoundedMessageQueueSemantics> {

  private final Map<Integer, Node> data;
  private final PolicyStats policyStats;
  private final EvictionPolicy policy;
  private final int maximumSize;
  private final Node sentinel;

  /**
   * Creates an actor that delegates to an LRU, FIFO, or CLOCK based cache.
   *
   * @param name the name of this policy
   * @param policy the eviction policy to apply
   */
  protected AbstractLinkedPolicy(String name, EvictionPolicy policy) {
    BasicSettings settings = new BasicSettings(this);
    this.maximumSize = settings.maximumSize();
    this.policyStats = new PolicyStats(name);
    this.data = new HashMap<>();
    this.sentinel = new Node();
    this.policy = policy;
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
        data.remove(event.keyHash());
        break;
      default:
        throw new UnsupportedOperationException();
    }
  }

  private void onCreateOrUpdate(TraceEvent event) {
    Node node = new Node(event.keyHash(), sentinel);
    Node old = data.putIfAbsent(node.key, node);
    if (old == null) {
      node.appendToTail();
      evict();
    } else {
      policy.onAccess(old);
    }
  }

  private void onRead(TraceEvent event) {
    Node node = data.get(event.keyHash());
    if (node == null) {
      policyStats.recordMiss();
    } else {
      policyStats.recordHit();
      policy.onAccess(node);
    }
  }

  /** Evicts while the map exceeds the maximum capacity. */
  private void evict() {
    while (data.size() > maximumSize) {
      Node node = sentinel.next;
      if (node == sentinel) {
        return;
      } else if (policy.onEvict(node)) {
        policyStats.recordEviction();
        data.remove(node.key);
        node.remove();
      }
    }
  }

  /** The replacement policy. */
  protected enum EvictionPolicy {

    /** Evicts entries based on insertion order. */
    FIFO() {
      @Override void onAccess(Node node) {
        // do nothing
      }
      @Override boolean onEvict(Node node) {
        return true;
      }
    },

    /**
     * Evicts entries based on insertion order, but gives an entry a "second chance" if it has been
     * requested recently.
     */
    CLOCK() {
      @Override void onAccess(Node node) {
        node.marked = true;
      }
      @Override boolean onEvict(Node node) {
        if (node.marked) {
          node.moveToTail();
          node.marked = false;
          return false;
        }
        return true;
      }
    },

    /** Evicts entries based on how recently they are used, with the most recent evicted first. */
    MRU() {
      @Override void onAccess(Node node) {
        node.moveToHead();
      }
      @Override boolean onEvict(Node node) {
        return true;
      }
    },

    /** Evicts entries based on how recently they are used, with the least recent evicted first. */
    LRU() {
      @Override void onAccess(Node node) {
        node.moveToTail();
      }
      @Override boolean onEvict(Node node) {
        return true;
      }
    };

    /** Performs any operations required by the policy after a node was successfully retrieved. */
    abstract void onAccess(Node node);

    /** Determines whether to evict the node at the head of the list. */
    abstract boolean onEvict(Node node);
  }

  /** A node on the double-linked list. */
  static final class Node {
    private static final Node UNLINKED = new Node();

    private final Node sentinel;
    private final Integer key;

    private boolean marked;
    private Node prev;
    private Node next;

    /** Creates a new sentinel node. */
    public Node() {
      this.sentinel = this;
      this.prev = this;
      this.next = this;
      this.key = null;
    }

    /** Creates a new, unlinked node. */
    public Node(Integer key, Node sentinel) {
      this.next = UNLINKED;
      this.prev = UNLINKED;
      this.sentinel = sentinel;
      this.key = key;
    }

    /** Appends the node to the tail of the list. */
    public void appendToTail() {
      // Allow moveToTail() to no-op
      next = sentinel;

      // Read the tail on the stack to avoid unnecessary volatile reads
      final Node tail = sentinel.prev;
      sentinel.prev = this;
      tail.next = this;
      prev = tail;
    }

    /** Removes the node from the list. */
    public void remove() {
      prev.next = next;
      next.prev = prev;
      next = UNLINKED; // mark as unlinked
    }

    /** Moves the node to the tail. */
    public void moveToHead() {
      if (isHead() || isUnlinked()) {
        return;
      }
      // unlink
      prev.next = next;
      next.prev = prev;

      // link
      next = sentinel.next; // ordered for isHead()
      prev = sentinel;
      sentinel.next = this;
      next.prev = this;
    }

    /** Moves the node to the tail. */
    public void moveToTail() {
      if (isTail() || isUnlinked()) {
        return;
      }
      // unlink
      prev.next = next;
      next.prev = prev;

      // link
      next = sentinel; // ordered for isTail()
      prev = sentinel.prev;
      sentinel.prev = this;
      prev.next = this;
    }

    /** Checks whether the node is linked on the list chain. */
    public boolean isUnlinked() {
      return (next == UNLINKED);
    }

    /** Checks whether the node is the first linked on the list chain. */
    public boolean isHead() {
      return (prev == sentinel);
    }

    /** Checks whether the node is the last linked on the list chain. */
    public boolean isTail() {
      return (next == sentinel);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("marked", marked)
          .toString();
    }
  }
}
