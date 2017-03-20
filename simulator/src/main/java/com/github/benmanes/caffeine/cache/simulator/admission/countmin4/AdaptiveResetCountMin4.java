/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache.simulator.admission.countmin4;

import javax.annotation.Nonnegative;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.BasicSettings.TinyLfuSettings.DoorkeeperSettings;
import com.github.benmanes.caffeine.cache.simulator.membership.FilterType;
import com.github.benmanes.caffeine.cache.simulator.membership.Membership;
import com.typesafe.config.Config;

/**
 * A sketch where the aging process is a dynamic process 
 * and adjusts to the recency/frequency bias of the actual workload.
 *
 * @author ben.manes@gmail.com (Ben Manes) and Gil Einziger
 */
public final class AdaptiveResetCountMin4 extends CountMin4 {
	static final long ONE_MASK = 0x1111111111111111L;

	final Membership doorkeeper;

	int additions;
	int period;
	int prevMisses ;      // misses in previous interval
	int misses =0;       // misses in this interval
	int direction =1;   // are we increasing the 'step size' or decreasing it
	int eventsToCount; // events yet to count before we make a decision. 
	public AdaptiveResetCountMin4(Config config) {
		super(config);
		this.prevMisses = period;
		BasicSettings settings = new BasicSettings(config);
		DoorkeeperSettings doorkeeperSettings = settings.tinyLfu().countMin4().periodic().doorkeeper();
		if (doorkeeperSettings.enabled()) {
			FilterType filterType = settings.membershipFilter();
			double expectedInsertionsMultiplier = doorkeeperSettings.expectedInsertionsMultiplier();
			long expectedInsertions = (long) expectedInsertionsMultiplier * settings.maximumSize();
			doorkeeper = filterType.create(expectedInsertions, doorkeeperSettings.fpp(), config);
		} else {
			doorkeeper = Membership.disabled();
		}
	}

	@Override
	protected void ensureCapacity(@Nonnegative long maximumSize) {
		super.ensureCapacity(maximumSize);
		period = (maximumSize == 0) ? 10 : (10 * table.length);
		if (period <= 0) {
			period = Integer.MAX_VALUE;
		}
		eventsToCount = this.period;

	}

	@Override
	public int frequency(long e) {
		int count = super.frequency(e);
		if (doorkeeper.mightContain(e)) {
			count++;
		}
		return count;
	}

	@Override
	public void increment(long e) {
		eventsToCount--;
		if (!doorkeeper.put(e)) {
			super.increment(e);
		}
	}

	/**
	 * Reduces every counter by half of its original value. To reduce the truncation error, the sample
	 * is reduced by the number of counters with an odd value.
	 */
	@Override
	protected void tryReset(boolean added) {
		additions+=this.step;

		if (!added) {
			return;
		}

		if (additions < period) {
			return;
		}

		int count = 0;
		for (int i = 0; i < table.length; i++) {
			count += Long.bitCount(table[i] & ONE_MASK);
			table[i] = (table[i] >>> 1) & RESET_MASK;
		}
		additions = (additions >>> 1) - (count >>> 2);
		doorkeeper.clear();
	}
	
	// this is where the new functionality is implemented. 
	// each time there is a miss - TinyLFU invokes the reportMiss function  
	// and we can make decisions. 
	@Override
	public void reportMiss() {

		this.misses++;
		if(eventsToCount <=0){
			eventsToCount = this.period; 

			/** 
			 * If this configuration is worse than the previous one, 
			 * switch directions. 
			 */
			if(this.misses > (this.prevMisses))
			{
				direction = -1*direction;

			}
			this.step -= direction;
			this.prevMisses = this.misses;
			this.misses = 0;
			if(this.step<1)
				this.step =1;
			if(this.step>15)
				this.step = 15;
		}	
	}

}

