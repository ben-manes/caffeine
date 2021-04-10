/*
 * Copyright 2015 Gilga Einziger. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.admission.table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Frequency;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

/**
 * A probabilistic multiset for estimating the popularity of an element within a time window. The
 * maximum frequency of an element. The size of the sample in relation to the cache size can be
 * controlled with a sample factor. Instead of halving the popularity of elements a random element
 * is dropped when table is full.
 *
 * This class is used to check the feasibility of using TinyTable instead of CountMin Sketch.
 *
 * @author gilg1983@gmail.com (Gil Einziger)
 */
public final class RandomRemovalFrequencyTable implements Frequency {
  /** sum of total items */
  private final int maxSum;
  /** total sum of stored items **/
  private int currSum;
  /** controls both the max count and how many items are remembered (the sum) */
  private static final int sampleFactor = 8;
  /** used to dropped items at random */
  private final Random random;
  /** a place holder for TinyTable */
  private final Map<Long, Integer> table;

  public RandomRemovalFrequencyTable(Config config) {
    BasicSettings settings = new BasicSettings(config);
    maxSum = Ints.checkedCast(sampleFactor * settings.maximumSize());
    random = new Random(settings.randomSeed());
    table = new HashMap<>(maxSum);
  }

  @Override
  public int frequency(long e) {
    return table.getOrDefault(e, 0);
  }

  @Override
  public void increment(long e) {
    // read and increments value
    int value = table.getOrDefault(e, 0) + 1;
    // if the value is big enough there is no point in dropping a value so we just quit
    if (value > sampleFactor) {
      return;
    }

    // putting the new value
    table.put(e, value);
    // advancing the number of items
    if (currSum < maxSum) {
      currSum++;
    }

    // Once the table is full every item that arrive some other item leaves. This implementation is
    // lacking as the probability to forget each item does not depend on frequency. (so items do not
    // converge to their true frequency. but I do not think it is worth fixing right now as this is
    // just a model.
    if (currSum == maxSum) {
      List<Long> array = new ArrayList<>(table.keySet());
      long itemToRemove = array.get(random.nextInt(array.size()));
      value = table.remove(itemToRemove);

      if (value > 1) {
        table.put(itemToRemove, value - 1);
      }
    }
  }
}
