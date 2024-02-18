package com.github.benmanes.caffeine.cache.simulator.policy.esp;


import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.SegmentedLruPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sampled.SampledPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.two_queue.TuQueuePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.two_queue.TwoQueuePolicy;
import com.tangosol.util.Base;
import com.typesafe.config.Config;
import com.github.benmanes.caffeine.cache.simulator.policy.esp.SharedBuffer;
import org.checkerframework.checker.units.qual.Length;

import java.util.*;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Locale.US;



public class PolicyConstructor{
  // POLICIES
  Config inConfig;
  PolicyStats IntraStats;
  static SampledSettings tempSampledSettings;
  SegmentedLruSettings tempSegmentedLRUSettings;
  public Object createPolicyObject;

public PolicyConstructor(Config config) {
  this.inConfig = config;
  this.IntraStats = new PolicyStats("Intra-Pipeline (IGNORE)");
}
  public Policy createPolicy(String policyName){
    switch (policyName) {
      case "LRU":
        tempSampledSettings = new SampledSettings(this.inConfig);
        return new SampledPolicy(Admission.ALWAYS, SampledPolicy.EvictionPolicy.LRU, tempSampledSettings.config());
      case "LFU":
        tempSampledSettings = new SampledSettings(this.inConfig);
        return new SampledPolicy(Admission.ALWAYS, SampledPolicy.EvictionPolicy.LFU, tempSampledSettings.config());
      case "SegmentedLRU":
        tempSegmentedLRUSettings = new SegmentedLruSettings(this.inConfig);
        return new SegmentedLruPolicy(Admission.ALWAYS, tempSegmentedLRUSettings.config());
      default:
        return null;
    }
  }


  // Define the custom TwoQueueSettings class with the overridden methods
  static class TwoQueueSettings extends BasicSettings {
    public TwoQueueSettings(Config config) {
      super(config);
    }

    public double percentIn() {
      // Redirect to relevant field in the config file
      return config().getDouble("esp.two-queue.percent-in");
    }

    public double percentOut() {
      // Redirect to relevant field in the config file
      return config().getDouble("esp.two-queue.percent-out");
    }
  }
  static class TuQueueSettings extends BasicSettings {

    public TuQueueSettings(Config config) {
      super(config);
    }

    public double percentHot() {
      double percentHot = config().getDouble("esp.tu-queue.percent-hot");
      checkState(percentHot < 1.0);
      return percentHot;
    }

    public double percentWarm() {
      double percentWarm = config().getDouble("esp.tu-queue.percent-warm");
      checkState(percentWarm < 1.0);
      return percentWarm;
    }
  }
  static class SampledSettings extends BasicSettings {
    public SampledSettings(Config config) {
      super(config);
    }

    public int sampleSize() {
      return config().getInt("esp.sampled.size");
    }

    public SampledPolicy.Sample sampleStrategy() {
      return SampledPolicy.Sample.valueOf(config().getString("esp.sampled.strategy").toUpperCase(US));
    }

  }
  static class SegmentedLruSettings extends BasicSettings {

    public SegmentedLruSettings(Config config) {
      super(config);
    }

    public double percentProtected() {
      return config().getDouble("esp.segmented-lru.percent-protected");
    }


  }

}

