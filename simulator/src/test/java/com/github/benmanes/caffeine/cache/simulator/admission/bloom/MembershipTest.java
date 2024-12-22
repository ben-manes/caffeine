/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.admission.bloom;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.Locale.US;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.simulator.membership.FilterType;
import com.github.benmanes.caffeine.cache.simulator.membership.Membership;
import com.github.benmanes.caffeine.cache.simulator.membership.bloom.BloomFilter;
import com.google.common.base.CaseFormat;
import com.google.errorprone.annotations.Var;
import com.jakewharton.fliptables.FlipTable;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

/**
 * @author ashish0x90 (Ashish Yadav)
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class MembershipTest {
  private static final Logger logger = LoggerFactory.getLogger(MembershipTest.class);

  private static final String[] HEADERS = { "Type", "Capacity", "Insertions", "False Positives" };
  private static final double EXPECTED_INSERTIONS_MULTIPLIER = 0.5;
  private static final double FPP = 0.03;

  @SuppressWarnings("Varifier")
  @Test(dataProvider = "filterTypes")
  public void bloomFilter(FilterType filterType) {
    var capacities = new IntArrayList(IntList.of(0, 1));
    for (int capacity = 2 << 10; capacity < (2 << 22); capacity <<= 2) {
      capacities.add(capacity);
    }

    var random = new Random();
    var rows = new ArrayList<String[]>();
    for (int capacity : capacities) {
      long[] input = random.longs(capacity).distinct().toArray();
      Config config = getConfig(filterType, capacity);

      Membership filter = filterType.create(config);
      int falsePositives = falsePositives(filter, input);
      int expectedInsertions = (int) (capacity * EXPECTED_INSERTIONS_MULTIPLIER);
      double falsePositiveRate = ((double) falsePositives / expectedInsertions);

      if (!Double.isNaN(falsePositiveRate)) {
        assertWithMessage(filterType.toString()).that(falsePositiveRate).isLessThan(FPP + 0.2);
      }
      rows.add(row(filterType, capacity, expectedInsertions, falsePositives, falsePositiveRate));
    }
    printTable(rows);
  }

  @Test(dataProvider = "ensureCapacity")
  public void caffeine_ensureCapacity(int expectedInsertions, double fpp) {
    var filter = new BloomFilter();
    filter.ensureCapacity(expectedInsertions, fpp);
    assertThat(filter.put(-1)).isTrue();
  }

  @DataProvider(name = "ensureCapacity")
  public Iterator<Object[]> providesExpectedInsertions() {
    return IntStream.range(0,  25).boxed().map(i -> new Object[] { i, FPP }).iterator();
  }

  @DataProvider(name = "filterTypes")
  public Object[] providesFilterTypes() {
    return FilterType.values();
  }

  /** Returns the false positives based on an input of unique elements. */
  private static int falsePositives(Membership filter, long[] input) {
    @Var int falsePositives = 0;
    @Var int truePositives = 0;
    @Var int i = 0;

    // Add only first half of input array
    for (; i < (input.length / 2); i++) {
      filter.put(input[i]);
    }

    // First half should be members
    for (int k = 0; k < i; k++) {
      truePositives += filter.mightContain(input[k]) ? 1 : 0;
    }
    assertThat(truePositives).isEqualTo(input.length / 2);

    // Second half shouldn't be members
    for (; i < input.length; i++) {
      falsePositives += filter.mightContain(input[i]) ? 1 : 0;
    }
    return falsePositives;
  }

  private static Config getConfig(FilterType filterType, int capacity) {
    var properties = Map.of(
        "membership.expected-insertions-multiplier", EXPECTED_INSERTIONS_MULTIPLIER,
        "membership.filter", filterType.name(),
        "maximum-size", capacity,
        "membership.fpp", FPP);
    return ConfigFactory.parseMap(properties)
        .withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
  }

  /** Returns a table row for printing the false positive rates of an implementation. */
  private static String[] row(FilterType filterType, int capacity,
      int expectedInsertions, int falsePositives, double falsePositiveRate) {
    return new String[] {
        CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, filterType.toString()),
        String.format(US, "%,d", capacity),
        String.format(US, "%,d", expectedInsertions),
        String.format(US, "%,d (%.2f %%)", falsePositives, 100 * falsePositiveRate),
    };
  }

  /** Displays the rows as a pretty printed table. */
  private static void printTable(List<String[]> rows) {
    var data = new String[rows.size()][HEADERS.length];
    for (int i = 0; i < rows.size(); i++) {
      data[i] = rows.get(i);
    }
    logger.info("\n{}", FlipTable.of(HEADERS, data));
  }
}
