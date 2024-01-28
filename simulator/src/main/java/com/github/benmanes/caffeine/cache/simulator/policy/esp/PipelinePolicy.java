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

//  private final PolicyStats pipelinePolicyStats;
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



  }

  @Override
  public void record(long key) {
  // Record the key in the TwoQueuePolicy instance
    //System.out.println("The key read from the buffer is: "+SharedBuffer.getInstance()+"moving to smapled recored()");

    superPolicy.sampledPolicy.record(key);
    //print the key and moving to segmentedLRUPolicy
    BaseNode node = SharedBuffer.getData();
    //System.out.println("The key read from the buffer is: "+node+"moving to segmented");
    superPolicy.segmentedLRUPolicy.record(key);
  }

  @Override
  public PolicyStats stats() {
    // You can also access and use the statistics from the TwoQueuePolicy instance
    return superPolicy.twoQueuePolicy.stats();
  }
  @Override
  public void finished() {
    // Ensure that all resources are properly cleaned up
    superPolicy.twoQueuePolicy.finished();
  }


}
