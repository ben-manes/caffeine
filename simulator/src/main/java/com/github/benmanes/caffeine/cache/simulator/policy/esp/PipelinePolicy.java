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
  final int maximumSize;
  private final HashMap<Long, Integer> lookUptable;
  long record_counter = 0;
  long evict_counter = 0;
  int maxEntries;

static class PipelineSettings extends BasicSettings {
  public PipelineSettings(Config config) {
    super(config);
  }
  public double pipelineLength() {
    // Redirect to relevant field in the config file
    return config().getDouble("pipeline.length");
  }
}
  public PipelinePolicy(Config config) {
    // Create an instance of SuperPolicy with the provided config
    //this.sharedBuffer = new SharedBuffer();
    System.out.println("Creating SuperPolicy");
    superPolicy = new SuperPolicy(config);
    this.pipeLineStats = new PolicyStats("PipeLine");
    PipelineSettings settings = new PipelineSettings(config);
    this.maximumSize = Math.toIntExact(settings.maximumSize());
//    lookUptable = new HashMap<>();
    this.maxEntries = 512;
      this.lookUptable=new HashMap<Long, Integer>();//load factor affects the results can also be used with linked HashMap;

}
  @Override
  public void record(long key) {

//    System.out.println(key);
//    System.out.println(lookUptable.get(key));
    if(lookUptable.get(key) != null) {
//      System.out.println("The key is already in the lookup table");
      pipeLineStats.recordOperation();
      pipeLineStats.recordHit();
      //UPDATE RELEVANT FIELDS AND PROPAGATE
      //we first need to evict the entry by calling the containing block using the lookup table
      //then using the shared buffer we can re-insert it into the pipeline
    } else {

      lookUptable.put(key, 1);
      pipeLineStats.recordAdmission();
      pipeLineStats.recordOperation();
      pipeLineStats.recordMiss();
//      System.out.println(superPolicy.sampledPolicy.maximumSize);
      superPolicy.sampledPolicy.record(key);
      if(SharedBuffer.getFlag() ==1){
//        System.out.println(superPolicy.segmentedLRUPolicy.maximumSize);

        superPolicy.segmentedLRUPolicy.record(SharedBuffer.getBufferKey());
      }
      if(SharedBuffer.getFlag2() ==1) {
        System.out.println( "table size before eviction "+lookUptable.size());
        lookUptable.remove(SharedBuffer.getBufferKey(),1);
        System.out.println( "table size after eviction "+lookUptable.size());
        pipeLineStats.recordEviction();
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


