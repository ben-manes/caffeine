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
  private LinkedHashMap<Long, String> lookUptable;
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
      this.lookUptable=new LinkedHashMap<Long, String>(maxEntries, 0.75f, true) {
//      @Override
//      protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
//        if(size() > maxEntries){
//          record_counter++;
//          System.out.println("internal evict:" + record_counter +"table size "+lookUptable.size());
//        }
//
//        return size() > maxEntries;
//        //print im here
//      }
    };

}
  @Override
  public void record(long key) {

//    System.out.println(key);
//    System.out.println(lookUptable.get(key));
    if(lookUptable.get(key) != null) {
      System.out.println("The key is already in the lookup table");
      pipeLineStats.recordOperation();
      pipeLineStats.recordHit();
      //UPDATE RELEVANT FIELDS AND PROPAGATE
      //we first need to evict the entry by calling the containing block using the lookup table
      //then using the shared buffer we can re-insert it into the pipeline
    } else {

      lookUptable.put(key, "valid");
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
//        System.out.println("lookup table size "+lookUptable.size());
        evict_counter++;
        System.out.println("external evict:" + evict_counter + "table size"+lookUptable.size());
        lookUptable.replace(SharedBuffer.getBufferKey(),null);
        pipeLineStats.recordEviction();
      }
    }
//    pipeLineStats.recordOperation();
//    pipeLineStats.recordHit();
    //superPolicy.sampledPolicy.record(key);
    //print the key and moving to segmentedLRUPolicy
    //BaseNode node = SharedBuffer.getData();
    //System.out.println("The key read from the buffer is: "+node+"moving to segmented");
    //superPolicy.segmentedLRUPolicy.record(key);

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


