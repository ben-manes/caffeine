package com.github.benmanes.caffeine.cache.simulator.policy;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.opt.UnboundedPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;


// A version of MyCachePolicy which implements the i/f Policy This is the i/f implemented by most the opt.UnboundedPolicy.
public final class MyCachePolicy implements Policy {
	  private final PolicyStats policyStats;
	  private final LongOpenHashSet data;

	  public MyCachePolicy() {
	    this.policyStats = new PolicyStats("MyCachePolicy");
	    this.data = new LongOpenHashSet();
	  }

	  /** Returns all variations of this policy based on the configuration parameters. */
	  public static Set<Policy> policies(Config config) {
	    return ImmutableSet.of(new MyCachePolicy());
	  }

	  @Override
	  public Set<Characteristic> characteristics() {
	    return Sets.immutableEnumSet(WEIGHTED);
	  }

	  @Override
	  public PolicyStats stats() {
	    return policyStats;
	  }

	  @Override
	  public void record(AccessEvent event) {
	    policyStats.recordOperation();
	    if (data.add(event.key().longValue())) {
	      policyStats.recordWeightedMiss(event.weight());
	    } else {
	      policyStats.recordWeightedHit(event.weight());
	    }
	  }
	}



// $$ A version of MyCachePolicy which implements the i/f KeyOnlyPolicy (defined within i/f Policy, within policy.java). This is the i/f 
// implemented by most non-opt policies.
//public class MyCachePolicy implements KeyOnlyPolicy {
//	  PolicyStats policyStats;
//
//	
//	  @Override
//	  public void record(long key) {
//	  }
//
//	  @Override
//	  public PolicyStats stats() {
//	    return policyStats;
//	  }
//	
//}
