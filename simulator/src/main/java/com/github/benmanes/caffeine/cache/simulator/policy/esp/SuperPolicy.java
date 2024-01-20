package com.github.benmanes.caffeine.cache.simulator.policy.esp;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
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

  // POLICIES
  TwoQueuePolicy twoQueuePolicy;
  TuQueuePolicy tuQueuePolicy;
  TwoQueueSettings customTwoQueueSettings;
  TuQueueSettings customTuQueueSettings;
  SampledPolicy LRUPolicy;
  SegmentedLruPolicy SegmentedLRUPolicy;
  LRUSettings customLRUSettings;
  SegmentedLRUSettings customSegmentedLRUSettings;
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
    TuQueuePolicy tuQueuePolicy = new TuQueuePolicy(customTuQueueSettings.config());
    tuQueuePolicy.policyStats = customPolicyStats;
    tuQueuePolicy.maxHot = (int) (tuQueuePolicy.maximumSize * customTuQueueSettings.percentHot());
    tuQueuePolicy.maxWarm = (int) (tuQueuePolicy.maximumSize * customTuQueueSettings.percentWarm());
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

  public static class TuQueueSettings extends BasicSettings {

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
  public static class LRUSettings extends  BasicSettings{
    public  LRUSettings(Config config){ super(config);}
    public int sampleSize() {
      return config().getInt("sampled.size");
    }
    public SampledPolicy.Sample sampleStrategy() {
      return SampledPolicy.Sample.valueOf(config().getString("sampled.strategy").toUpperCase(US));
    }

  }
}

