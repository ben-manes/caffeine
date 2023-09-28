//add to configue
package com.github.benmanes.caffeine.cache.simulator.policy.esp;



import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

//import com.github.benmanes.caffeine.cache.simulator.policy.two_queue.TwoQueuePolicy;
public final class PipelinePolicy implements KeyOnlyPolicy {

  static final PipelinePolicy.Node UNLINKED = new PipelinePolicy.Node();

  final Long2ObjectMap<PipelinePolicy.Node> data;
  final PolicyStats policyStats;
  final int maximumSize;

  int sizeIn;
  final int maxIn;
  final PipelinePolicy.Node headIn;

  int sizeOut;
  final int maxOut;
  final PipelinePolicy.Node headOut;

  int sizeMain;
  final PipelinePolicy.Node headMain;

  public PipelinePolicy(Config config) {
    PipelinePolicy.PipelinePolicySettings settings = new PipelinePolicy.PipelinePolicySettings(config);

    this.headIn = new PipelinePolicy.Node();
    this.headOut = new PipelinePolicy.Node();
    this.headMain = new PipelinePolicy.Node();
    this.data = new Long2ObjectOpenHashMap<>();
    this.policyStats = new PolicyStats(name());
    this.maximumSize = Math.toIntExact(settings.maximumSize());
    this.maxIn = (int) (maximumSize * settings.percentIn());
    this.maxOut = (int) (maximumSize * settings.percentOut());
  }

  @Override
  @SuppressWarnings({"PMD.ConfusingTernary", "PMD.SwitchStmtsShouldHaveDefault"})
  public void record(long key) {
    // On accessing a page X :
    //   if X is in Am then
    //     move X to the head of Am
    //   else if (X is in Alout) then
    //     reclaimfor(X)
    //     add X to the head of Am
    //   else if (X is in Alin)
    //     // do nothing
    //   else // X is in no queue
    //     reclaimfor(X)
    //     add X to the head of Alin
    //   end if

    policyStats.recordOperation();
    PipelinePolicy.Node node = data.get(key);
    if (node != null) {
      switch (node.type) {
        case MAIN:
          node.moveToTail(headMain);
          policyStats.recordHit();
          return;
        case OUT:
          node.remove();
          sizeOut--;

          reclaimfor(node);

          node.appendToTail(headMain);
          node.type = PipelinePolicy.QueueType.MAIN;
          sizeMain++;

          policyStats.recordMiss();
          return;
        case IN:
          // do nothing
          policyStats.recordHit();
          return;
      }
    } else {
      node = new PipelinePolicy.Node(key);
      node.type = PipelinePolicy.QueueType.IN;

      reclaimfor(node);
      node.appendToTail(headIn);
      sizeIn++;

      policyStats.recordMiss();
    }
  }

  private void reclaimfor(PipelinePolicy.Node node) {
    // if there are free page slots then
    //   put X into a free page slot
    // else if (size(Alin) > Kin)
    //   page out the tail of Alin, call it Y
    //   add identifier of Y to the head of Alout
    //   if (size(Alout) > Kout)
    //     remove identifier of Z from the tail of Alout
    //   end if
    //   put X into the reclaimed page slot
    // else
    //   page out the tail of Am, call it Y
    //   // do not put it on Alout; it hasn’t been accessed for a while
    //   put X into the reclaimed page slot
    // end if

    if ((sizeMain + sizeIn) < maximumSize) {
      data.put(node.key, node);
    } else if (sizeIn > maxIn) {
      // IN is full, move to OUT
      PipelinePolicy.Node n = headIn.next;
      n.remove();
      sizeIn--;
      n.appendToTail(headOut);
      n.type = PipelinePolicy.QueueType.OUT;
      sizeOut++;

      if (sizeOut > maxOut) {
        // OUT is full, drop oldest
        policyStats.recordEviction();
        PipelinePolicy.Node victim = headOut.next;
        data.remove(victim.key);
        victim.remove();
        sizeOut--;
      }
      data.put(node.key, node);
    } else {
      // OUT has room, evict from MAIN
      policyStats.recordEviction();
      PipelinePolicy.Node victim = headMain.next;
      data.remove(victim.key);
      victim.remove();
      sizeMain--;
      data.put(node.key, node);
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  enum QueueType {
    MAIN,
    IN,
    OUT,
  }

  static final class Node {
    final long key;

    PipelinePolicy.Node prev;
    PipelinePolicy.Node next;
    PipelinePolicy.QueueType type;

    Node() {
      this.key = Long.MIN_VALUE;
      this.prev = this;
      this.next = this;
    }

    Node(long key) {
      this.key = key;
      this.prev = UNLINKED;
      this.next = UNLINKED;
    }

    /**
     * Appends the node to the tail of the list.
     */
    public void appendToTail(PipelinePolicy.Node head) {
      PipelinePolicy.Node tail = head.prev;
      head.prev = this;
      tail.next = this;
      next = head;
      prev = tail;
    }

    /**
     * Moves the node to the tail.
     */
    public void moveToTail(PipelinePolicy.Node head) {
      // unlink
      prev.next = next;
      next.prev = prev;

      // link
      next = head;
      prev = head.prev;
      head.prev = this;
      prev.next = this;
    }

    /**
     * Removes the node from the list.
     */
    public void remove() {
      prev.next = next;
      next.prev = prev;
      prev = next = UNLINKED; // mark as unlinked
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
        .add("key", key)
        .add("type", type)
        .toString();
    }
  }

  static final class PipelinePolicySettings extends BasicSettings {

    public PipelinePolicySettings(Config config) {
      super(config);
    }

    public double percentIn() {
      return config().getDouble("pipeline.percent-in");
    }

    public double percentOut() {
      return config().getDouble("pipeline.percent-out");
    }
  }
}



