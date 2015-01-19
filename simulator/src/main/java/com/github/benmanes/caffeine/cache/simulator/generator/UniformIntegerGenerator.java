/*
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

/**
 * Generates integers randomly uniform from an interval.
 *
 * https://github.com/brianfrankcooper/YCSB
 */
public class UniformIntegerGenerator extends IntegerGenerator
{
	int _lb,_ub,_interval;

	/**
	 * Creates a generator that will return integers uniformly randomly from the interval [lb,ub] inclusive (that is, lb and ub are possible values)
	 *
	 * @param lb the lower bound (inclusive) of generated values
	 * @param ub the upper bound (inclusive) of generated values
	 */
	public UniformIntegerGenerator(int lb, int ub)
	{
		_lb=lb;
		_ub=ub;
		_interval=_ub-_lb+1;
	}

	@Override
	public int nextInt()
	{
		int ret=Utils.random().nextInt(_interval)+_lb;
		setLastInt(ret);

		return ret;
	}

	@Override
	public double mean() {
		return ((_lb + (long)_ub)) / 2.0;
	}
}
