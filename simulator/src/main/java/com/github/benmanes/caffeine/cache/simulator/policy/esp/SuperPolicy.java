package com.github.benmanes.caffeine.cache.simulator.policy.esp;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.two_queue.TuQueuePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.two_queue.TwoQueuePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sampled.SampledPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.SegmentedLruPolicy;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigValueFactory;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Locale.US;

public class SuperPolicy {
// Shared Memory buffer



  // POLICIES
  TwoQueuePolicy twoQueuePolicy;
  TwoQueueSettings customTwoQueueSettings;

  TuQueuePolicy tuQueuePolicy;
  TuQueueSettings customTuQueueSettings;

  SampledPolicy sampledPolicy;
  SampledSettings customSampledSettings;

  SegmentedLruPolicy segmentedLRUPolicy;
  SegmentedLruSettings customSegmentedLRUSettings;



  public SuperPolicy(Config config) {


    PolicyStats customPolicyStats = new PolicyStats("PipeLine");
    // -------------Two Queue Instance -------------
    customTwoQueueSettings = new TwoQueueSettings(config);
    twoQueuePolicy = new TwoQueuePolicy(customTwoQueueSettings.config());
    twoQueuePolicy.policyStats = customPolicyStats;
    twoQueuePolicy.maxIn = (int) (twoQueuePolicy.maximumSize * customTwoQueueSettings.percentIn());
    twoQueuePolicy.maxOut = (int) (twoQueuePolicy.maximumSize * customTwoQueueSettings.percentOut());
    //----------------------------------------------

    //-----------------Tu Queue Instance -------------
    customTuQueueSettings = new TuQueueSettings(config);
    tuQueuePolicy = new TuQueuePolicy(customTuQueueSettings.config());
    tuQueuePolicy.policyStats = customPolicyStats;
    tuQueuePolicy.maxHot = (int) (tuQueuePolicy.maximumSize * customTuQueueSettings.percentHot());
    tuQueuePolicy.maxWarm = (int) (tuQueuePolicy.maximumSize * customTuQueueSettings.percentWarm());
    //----------------------------------------------

    // -------------SampledLRUPolicy Instance -------------
    System.out.println("Creating LRUPolicy");
    customSampledSettings = new SampledSettings(config);
    sampledPolicy = new SampledPolicy(Admission.ALWAYS, SampledPolicy.EvictionPolicy.LRU,customSampledSettings.config());
    sampledPolicy.policyStats = customPolicyStats;
    sampledPolicy.sampleSize = customSampledSettings.sampleSize();
    sampledPolicy.sampleStrategy = customSampledSettings.sampleStrategy();
    //----------------------------------------------

    //-----------------Segmented LRU Instance -------------
    System.out.println("Creating SegmentedLRUPolicy");
    customSegmentedLRUSettings = new SegmentedLruSettings(config);
    segmentedLRUPolicy = new SegmentedLruPolicy(Admission.ALWAYS,customSegmentedLRUSettings.config());
    segmentedLRUPolicy.policyStats = customPolicyStats;
    segmentedLRUPolicy.maxProtected = (int) (segmentedLRUPolicy.maximumSize * customSegmentedLRUSettings.percentProtected());
    //----------------------------------------------



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
