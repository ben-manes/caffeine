package com.github.benmanes.caffeine.cache.simulator.policy.TinyCache;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.tinyCache.TinyCache;
import com.github.benmanes.caffeine.cache.simulator.admission.tinyCache.TinyCacheWithGhostCache;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;

public class WindowTinyCachePolicy implements Policy {

	  private final PolicyStats policyStats;
	  
	  TinyCache window;
	  TinyCacheWithGhostCache tinyCache; 
	public WindowTinyCachePolicy(String name,Config config)
	{
	    BasicSettings settings = new BasicSettings(config);
		policyStats = new PolicyStats(name);
		int maxSize = settings.maximumSize(); 
		window = new TinyCache(1, 64, 0);
		maxSize-=64;
		tinyCache = new TinyCacheWithGhostCache((int) Math.ceil(maxSize/64.0), 64,settings.randomSeed());
	}
	@Override
	public void record(Comparable<Object> key) {
		
		if(tinyCache.contains(key.hashCode())|| window.contains(key.hashCode()))
		{
			tinyCache.recordItem(key.hashCode());
			policyStats.recordHit();
		}
		else
		{
			boolean evicted = tinyCache.addItem(key.hashCode());
			if(!evicted)
			{
				evicted = window.addItem(key.hashCode());
				
			}
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
