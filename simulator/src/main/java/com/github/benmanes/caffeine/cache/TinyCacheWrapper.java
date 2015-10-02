package com.github.benmanes.caffeine.cache;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Frequency;
import com.github.benmanes.caffeine.cache.simulator.admission.tinyCache.TinyCacheSketch;
import com.typesafe.config.Config;
/**
 * A wrapper for TinyCache implementation of admission policy. 
 * @author gilga1983@gmail.com (Gil Einziger)
 *
 * @param <E>
 */
public class TinyCacheWrapper<E>  implements Frequency<E> {
	// the actual data structure. 
	TinyCacheSketch tcs;
	// number of (independent sets)
	int nrSets; 
	// size between cache and sample. 
	final int sampleFactor =8; 
	// max frequency estimation of an item. 
	final int maxcount =10;
/**
 * Note that in this implementation there are always 64 items per set. 
 * @param config
 */
	public TinyCacheWrapper(Config config) {
		 BasicSettings settings = new BasicSettings(config);
		 nrSets = sampleFactor * settings.maximumSize()/64;
		tcs = new TinyCacheSketch(nrSets, 64);
	}

	@Override
	public int frequency(E e) {
		return tcs.countItem(e.hashCode());
		
	}

	@Override
	public void increment(E e) {
		if(tcs.countItem(e.hashCode()) < (maxcount))
			tcs.addItem(e.hashCode());
		
	}
}
