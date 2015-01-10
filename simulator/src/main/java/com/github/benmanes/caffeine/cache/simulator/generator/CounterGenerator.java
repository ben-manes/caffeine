/**
 * Copyright (c) 2010 Yahoo! Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package com.github.benmanes.caffeine.cache.simulator.generator;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates a sequence of integers 0, 1, ...
 *
 * @see https://github.com/brianfrankcooper/YCSB
 */
public class CounterGenerator extends IntegerGenerator
{
	final AtomicInteger counter;

	/**
	 * Create a counter that starts at countstart
	 */
	public CounterGenerator(int countstart)
	{
		counter=new AtomicInteger(countstart);
		setLastInt(counter.get()-1);
	}

	/**
	 * If the generator returns numeric (integer) values, return the next value as an int. Default is to return -1, which
	 * is appropriate for generators that do not return numeric values.
	 */
	@Override
  public int nextInt()
	{
		int ret = counter.getAndIncrement();
		setLastInt(ret);
		return ret;
	}
	@Override
	public int lastInt()
	{
	                return counter.get() - 1;
	}
	@Override
	public double mean() {
		throw new UnsupportedOperationException("Can't compute mean of non-stationary distribution!");
	}
}
