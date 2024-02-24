package com.github.benmanes.caffeine.cache.simulator.policy.esp;


import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.greedy_dual.GDWheelPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sampled.SampledPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.SegmentedLruPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.two_queue.TwoQueuePolicy;
import com.tangosol.util.Base;
import com.typesafe.config.Config;
import com.github.benmanes.caffeine.cache.simulator.policy.esp.SuperPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.esp.SharedBuffer;
import org.checkerframework.checker.units.qual.Length;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import java.util.*;
import static java.util.Locale.US;


/**
 * Your PipelinePolicy class.
 * <p>
 * This implementation is based on your PipelinePolicy class. You can access and use methods from
 * the TuQueuePolicy instance as needed.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "esp.PipelinePolicy")
public final class PipelinePolicy implements KeyOnlyPolicy {

  private final SuperPolicy superPolicy;
  public PolicyStats pipeLineStats;

  public String pipelineOrder;
  final int maximumSize;
  private final HashMap<Long, Integer> lookUptable;
  int maxEntries;
  String pipelineList;
  int pipeline_length;
  String[] pipelineArray;
  List<Policy> pipelinePolicies =new ArrayList<>();
  PolicyConstructor policyConstructor;
  Config confTest;
  int extCount=0; //used for tracking nodes in the pipeline + pipeline current stage

  static class PipelineSettings extends BasicSettings {
  public PipelineSettings(Config config) {
    super(config);
  }
  public double pipelineLength() {
    // Redirect to relevant field in the config file
    return config().getDouble("pipeline.length");
  }
  public String pipelineOrder() {
    // Redirect to relevant field in the config file
    return config().getString("esp.pipeline.order");
  }
}
  public PipelinePolicy(Config config) {
    this.policyConstructor = new PolicyConstructor(config);
//------------------INIT--------------------
    superPolicy = new SuperPolicy(config);
    this.pipeLineStats = new PolicyStats("PipeLine");
    PipelineSettings settings = new PipelineSettings(config);
    this.maximumSize = Math.toIntExact(settings.maximumSize());
//    this.maxEntries = 512;
    //NOTE - the lookup table structure is affecting the results, each run is different
    this.lookUptable = new HashMap<Long, Integer>();//load factor affects the results can also be used with linked HashMap;
    //------------EXTRACT THE PIPELINE ORDER----------------
    this.pipelineList = settings.pipelineOrder();
    this.pipelineArray = this.pipelineList.split(",");
    this.pipeline_length = this.pipelineArray.length;
    System.out.println("pipeline lengtgh is " + this.pipeline_length);

    //-----------------BUILD THE PIPELINE-------------------
//    policyConstructor = new PolicyConstructor(config);
    for (int i = 0; i < this.pipeline_length; i++) {
      pipelinePolicies.add(this.policyConstructor.createPolicy(this.pipelineArray[i]));
//      pipelinePolicies.add(superPolicy.segmentedLRUPolicy);
//      pipelinePolicies.add(superPolicy.gdWheelPolicy);

    }
  }

  @Override
  public void record(long key) {
    extCount =0;
    //-------PIPELINE OPERATION-----------
    //1.check if hit
    //  miss- insert to lookup table and write to SharedBuffer
    //  hit - use lookup table to locate the block number - j
    //        now use pipelinePolices[j] to call the block holding the entry
    //        and do pipelinePolices[j].onHit() and update relevant fields (e.g freq)
    //        ------maybe propagation-----
    //2.in loop: pipelinePolices[j].record(), if SharedBuffer is increased by 1:
    //           activate the next block
    //           if counter == pipelineLength: evict from the lookup table
    //

    //------------ON HIT----------
    if(lookUptable.get(key) != null) {
      pipeLineStats.recordOperation();
      pipeLineStats.recordHit();
      int blockIndex = lookUptable.get(key);
      if (pipelinePolicies.get(blockIndex) instanceof KeyOnlyPolicy) {
        // Handle the event as a key-only event
//        ((KeyOnlyPolicy) pipelinePolicies.get(blockIndex)).data.remove(key);
      } else {
        // Handle the event for a generic policy
        AccessEvent event = new AccessEvent(key/* Additional details here */);
//        pipelinePolicies.get(blockIndex).data.remove(event.key);
      }

      //PROPAGATION

      //------------ON MISS----------
    } else {
      lookUptable.put(key, 1);
      pipeLineStats.recordAdmission();
      pipeLineStats.recordOperation();
      pipeLineStats.recordMiss();
      }
    //------------PIPELINE OPERATION----------
    //1. First block always admits
    if (pipelinePolicies.get(0) instanceof KeyOnlyPolicy) {
      // Handle the event as a key-only event
      ((KeyOnlyPolicy) pipelinePolicies.get(0)).record(key);
    } else {
      // Handle the event for a generic policy
      // If Policy expects a more detailed event, construct it before recording
      AccessEvent event = new AccessEvent(key/* Additional details here */);
      pipelinePolicies.get(0).record(event);
    }
    //----------MAIN PIPELINE LOOP----------

for (int i = 0; i < this.pipeline_length; i++) {
        //Read from the SharedBuffer
        extCount = SharedBuffer.getCounter();
        //If the SharedBuffer is increased by 1, activate the next block
        if(extCount==i) {
          //If the current block is the last block, evict from the lookup table
          if(i==this.pipeline_length-1) {
            lookUptable.remove(key);
          }
          //Activate the next block
          if (pipelinePolicies.get(i) instanceof KeyOnlyPolicy) {
            // Handle the event as a key-only event
            ((KeyOnlyPolicy) pipelinePolicies.get(i)).record(key);
          } else {
            // Handle the event for a generic policy
            AccessEvent event = new AccessEvent(key/* Additional details here */);
            pipelinePolicies.get(i).record(event);
          }

        }
      }

    }

  @Override
  public PolicyStats stats() {
    // You can also access and use the statistics from the TwoQueuePolicy instance
    //return superPolicy.twoQueuePolicy.stats();
    return pipeLineStats;

  }
  @Override
  public void finished() {
    // Ensure that all resources are properly cleaned up
//    System.out.println(data.size());
    superPolicy.twoQueuePolicy.finished();
  }



}


