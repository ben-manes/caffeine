package com.github.benmanes.caffeine.cache.simulator.policy.esp;


import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.sampled.SampledPolicy;
import com.tangosol.util.Base;
import com.typesafe.config.Config;
import com.github.benmanes.caffeine.cache.simulator.policy.esp.SuperPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.esp.SharedBuffer;
import org.checkerframework.checker.units.qual.Length;

import java.security.Policy;
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
  long record_counter = 0;
  long evict_counter = 0;
  int maxEntries;
  String pipelineList;
  int pipeline_length;
  String[] pipelineArray;
  KeyOnlyPolicy[] pipelinePolicies;



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
//------------------INIT--------------------
    System.out.println("Creating SuperPolicy");
    superPolicy = new SuperPolicy(config);
    this.pipeLineStats = new PolicyStats("PipeLine");
    PipelineSettings settings = new PipelineSettings(config);
    this.maximumSize = Math.toIntExact(settings.maximumSize());
    this.maxEntries = 512;
    //NOTE - the lookup table structure is affecting the results, each run is different
    this.lookUptable=new HashMap<Long, Integer>();//load factor affects the results can also be used with linked HashMap;
    //------------BUILD THE PIPELINE ORDER----------------
    this.pipelineList = settings.pipelineOrder();
    this.pipelineArray = this.pipelineList.split(",");
    this.pipeline_length = this.pipelineArray.length;
    System.out.println("pipeline lengtgh is "+this.pipeline_length);
    for (int i = 0; i < this.pipeline_length; i++) {
      pipelinePolicies[i] =  policyConstructor(this.pipelineArray[i],i);

    }
    //ADD MAP FROM LIST TO POLICY
}

  @Override
  public void record(long key) {
    //IF HIT
    if(lookUptable.get(key) != null) {
      pipeLineStats.recordOperation();
      pipeLineStats.recordHit();
      //NEED TO DO PIPELINE PROPAGATION

      //IF MISS
    } else {
      lookUptable.put(key, 1);
      pipeLineStats.recordAdmission();
      pipeLineStats.recordOperation();
      pipeLineStats.recordMiss();
      pipelinePolicies[j].record(key);
      //if the previous block evicted than it will increase the counter
      //1. check if counter was increased, if yes move to the next block
      //2. if counter reached max_value than last block evicted and the
      //  entry needs to be deleted from the lookuptable and reset j;
      while(SharedBuffer.getCounter() == j+1){
        j++;
        pipelinePolicies[j].record(SharedBuffer.getBufferKey());
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
  KeyOnlyPolicy policyConstructor(String policyName,int policyNumber) {
    switch (policyName) {
      case "LRU":
        return  superPolicy.LRUPolicyConstructor(policyNumber);
      case "SegmentedLruPolicy":
        return superPolicy.segmentedLRUPolicy;
      default:
        return null;
    }
  }
}


