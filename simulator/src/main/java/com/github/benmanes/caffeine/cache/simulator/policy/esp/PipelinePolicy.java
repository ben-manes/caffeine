package com.github.benmanes.caffeine.cache.simulator.policy.esp;


import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;
import com.github.benmanes.caffeine.cache.simulator.policy.esp.SuperPolicy;



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
  public PipelinePolicy(Config config) {
    // Create an instance of SuperPolicy with the provided config
    superPolicy = new SuperPolicy(config);
//    pipelinePolicyStats = new PolicyStats(name());
  }

  @Override
  public void record(long key) {
    // Access and use the TwoQueuePolicy instance from SuperPolicy
    superPolicy.twoQueuePolicy.record(key);
    //add print for logging purposes


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
