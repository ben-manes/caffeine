package com.github.benmanes.caffeine.cache.simulator.policy.linked;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import static java.util.Locale.US;
import static java.util.stream.Collectors.toSet;

import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

public class MyLinkedPolicy implements Policy { //$$ 
  final Long2ObjectMap<Node> data;
  protected PolicyStats policyStats; //$$ Chanted to protected, for letting sub-class MyGenericPolicy to access it
  final EvictionPolicy policy;
  final Admittor admittor;
  final int maximumSize;
  final Node sentinel;
  int currentSize;
  
  public MyLinkedPolicy (Admission admission, EvictionPolicy policy, Config config) { //$$
    this.policyStats = new PolicyStats(admission.format("my_linked." + policy.label())); //$$
    this.admittor = admission.from(config, policyStats);
    BasicSettings settings = new BasicSettings(config);
    this.data = new Long2ObjectOpenHashMap<>();
    this.maximumSize = settings.maximumSize();
    this.sentinel = new Node();
    this.policy = policy;
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config, EvictionPolicy policy) {
    BasicSettings settings = new BasicSettings(config);
    return settings.admission().stream().map(admission ->
      new MyLinkedPolicy(admission, policy, config) //$$
    ).collect(toSet());
  }

  @Override
  public Set<Characteristic> characteristics() {
    return Sets.immutableEnumSet(WEIGHTED);  
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }
  
  //$$
  public boolean IsInCache (long key) {
    return (data.get(key) == null)? false:true;   
  }
  
  @Override
  public void record(AccessEvent event) {
    final int weight = event.weight();
    final long key = event.key();
    Node old = data.get(key);
    admittor.record(key);
    if (old == null) { // miss
      
      // Write stats about the miss
      policyStats.recordWeightedMiss(weight);
      if (weight > maximumSize) {
        policyStats.recordOperation();
        return;
      }
      
      // cache the (missed) requested key  
      Node node = new Node(key, weight, sentinel);
      data.put(key, node);
      currentSize += node.weight;
      node.appendToTail();
      evict(node);
    } 
    
    else { // hit
      policyStats.recordWeightedHit(weight);
      policy.onAccess(old, policyStats);
    }
  }

  /** Evicts while the map exceeds the maximum capacity. */
  private void evict(Node candidate) {
    if (currentSize > maximumSize) {
      while (currentSize > maximumSize) {
        Node victim = policy.findVictim(sentinel, policyStats);
        policyStats.recordEviction();
  
        boolean admit = admittor.admit(candidate.key, victim.key);
        if (admit) {
          evictEntry(victim);
        } else {
          evictEntry(candidate);
        }
      }
    } else {
      policyStats.recordOperation();
    }
  }

  //$$ Inform other classes about an evicted key.
  // The function is empty - to be overriden by other classes.
  public void IndicateEviction (long key) {
  }
  
  
  // This function is private, and uses the private class Node. 
  // Hence, it's hard to intercepted / override it by another class.
  // To solve it, I added the call to IndicateEviction in the end.
  private void evictEntry(Node node) {
    currentSize -= node.weight;
    data.remove(node.key);
    node.remove();
    IndicateEviction (node.key); //$$ Inform other classes about the eviction  
  }

  /** The replacement policy. */
  public enum EvictionPolicy {

    /** Evicts entries based on insertion order. */
    FIFO {
      @Override void onAccess(Node node, PolicyStats policyStats) {
        policyStats.recordOperation();
        // do nothing
      }
      @Override Node findVictim(Node sentinel, PolicyStats policyStats) {
        policyStats.recordOperation();
        return sentinel.next;
      }
    },

    /**
     * Evicts entries based on insertion order, but gives an entry a "second chance" if it has been
     * requested recently.
     */
    CLOCK {
      @Override void onAccess(Node node, PolicyStats policyStats) {
        policyStats.recordOperation();
        node.marked = true;
      }
      @Override Node findVictim(Node sentinel, PolicyStats policyStats) {
        for (;;) {
          policyStats.recordOperation();
          Node node = sentinel.next;
          if (node.marked) {
            node.moveToTail();
            node.marked = false;
          } else {
            return node;
          }
        }
      }
    },

    /** Evicts entries based on how recently they are used, with the most recent evicted first. */
    MRU {
      @Override void onAccess(Node node, PolicyStats policyStats) {
        policyStats.recordOperation();
        node.moveToTail();
      }
      @Override Node findVictim(Node sentinel, PolicyStats policyStats) {
        policyStats.recordOperation();
        // Skip over the added entry
        return sentinel.prev.prev;
      }
    },

    /** Evicts entries based on how recently they are used, with the least recent evicted first. */
    LRU {
      @Override void onAccess(Node node, PolicyStats policyStats) {
        policyStats.recordOperation();
        node.moveToTail();
      }
      @Override Node findVictim(Node sentinel, PolicyStats policyStats) {
        policyStats.recordOperation();
        return sentinel.next;
      }
    };

    public String label() {
      return StringUtils.capitalize(name().toLowerCase(US));
    }

    /** Performs any operations required by the policy after a node was successfully retrieved. */
    abstract void onAccess(Node node, PolicyStats policyStats);

    /** Returns the victim entry to evict. */
    abstract Node findVictim(Node sentinel, PolicyStats policyStats);
  }

  /** A node on the double-linked list. */
  static final class Node {
    final Node sentinel;

    boolean marked;
    Node prev;
    Node next;
    long key;
    int weight;

    /** Creates a new sentinel node. */
    public Node() {
      this.key = Long.MIN_VALUE;
      this.sentinel = this;
      this.prev = this;
      this.next = this;
    }

    /** Creates a new, unlinked node. */
    public Node(long key, int weight, Node sentinel) {
      this.sentinel = sentinel;
      this.key = key;
      this.weight = weight;
    }

    /** Appends the node to the tail of the list. */
    public void appendToTail() {
      Node tail = sentinel.prev;
      sentinel.prev = this;
      tail.next = this;
      next = sentinel;
      prev = tail;
    }

    /** Removes the node from the list. */
    public void remove() {
      prev.next = next;
      next.prev = prev;
      prev = next = null;
      key = Long.MIN_VALUE;
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
          .add("marked", marked)
          .toString();
    }
  }
}

