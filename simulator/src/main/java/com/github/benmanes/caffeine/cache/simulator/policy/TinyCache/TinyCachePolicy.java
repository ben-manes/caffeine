package com.github.benmanes.caffeine.cache.simulator.policy.TinyCache;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.tinyCache.TinyCache;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;

public class TinyCachePolicy implements Policy {

	  private final PolicyStats policyStats;
	  
	  TinyCache tinyCache; 
	public TinyCachePolicy(String name,Config config)
	{
	    BasicSettings settings = new BasicSettings(config);
		policyStats = new PolicyStats(name);
		tinyCache = new TinyCache((int) Math.ceil(settings.maximumSize()/64.0), 64,settings.randomSeed());
	}
	@Override
	public void record(Comparable<Object> key) {
		
		if(tinyCache.contains(key.hashCode()))
		{
			tinyCache.recordItem(key.hashCode());
			policyStats.recordHit();
		}
		else
		{
			boolean evicted = tinyCache.addItem(key.hashCode());
			
			tinyCache.recordItem(key.hashCode());
			policyStats.recordMiss();
			if(evicted)
				policyStats.recordEviction();
		}

		
		
	}

	@Override
	public PolicyStats stats() {
		
		return policyStats;
	}

}
