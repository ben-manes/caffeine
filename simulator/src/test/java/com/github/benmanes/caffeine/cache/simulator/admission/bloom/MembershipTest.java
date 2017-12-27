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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.simulator.membership.FilterType;
import com.github.benmanes.caffeine.cache.simulator.membership.Membership;
import com.jakewharton.fliptables.FlipTable;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * @author ashish0x90 (Ashish Yadav)
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class MembershipTest {
  static final double FPP = 0.03;
  static final int SEED = 1033096058;
  static final String[] HEADERS = { "Type", "Insertions", "False Positives" };
  static final Config CONFIG = ConfigFactory.parseString("random-seed=" + SEED);

  static final boolean display = false;

  @Test(dataProvider = "filterTypes")
  public void bloomFilterTest(FilterType filterType) {
    for (int capacity = 2 << 10; capacity < (2 << 22); capacity = capacity << 2) {
      long[] input = new Random().longs(capacity).distinct().toArray();
      List<String[]> rows = new ArrayList<>();
      int expectedInsertions = capacity / 2;

      Membership filter = filterType.create(expectedInsertions, FPP, CONFIG);
      int falsePostives = falsePostives(filter, input);
      double falsePositiveRate = ((double) falsePostives / expectedInsertions);
      assertThat(filterType.toString(), falsePositiveRate, is(lessThan(FPP + 0.01)));
      rows.add(row(filterType, expectedInsertions, falsePostives, falsePositiveRate));

      if (display) {
        printTable(rows);
      }
    }
  }

  @DataProvider(name = "filterTypes")
  public Object[] providesFilterTypes() {
    return FilterType.values();
  }

  /** Returns the false positives based on an input of unique elements. */
  private int falsePostives(Membership filter, long[] input) {
    int falsePositives = 0;
    int truePositives = 0;
    int i = 0;

    // Add only first half of input array
    for (; i < (input.length / 2); i++) {
      filter.put(input[i]);
    }

    // First half should be members
    for (int k = 0; k < i; k++) {
      truePositives += filter.mightContain(input[k]) ? 1 : 0;
    }
    assertThat(truePositives, is(input.length / 2));

    // Second half shouldn't be members
    for (; i < input.length; i++) {
      falsePositives += filter.mightContain(input[i]) ? 1 : 0;
    }
    return falsePositives;
  }

  /** Returns a table row for printing the false positive rates of an implementation. */
  private static String[] row(FilterType filterType, int expectedInsertions,
      int falsePositives, double falsePositiveRate) {
    return new String[] {
        filterType.toString(),
        String.format("%,d", expectedInsertions),
        String.format("%,d (%.2f %%)", falsePositives, 100 * falsePositiveRate),
    };
  }

  /** Displays the rows as a pretty printed table. */
  private static void printTable(List<String[]> rows) {
    String[][] data = new String[rows.size()][HEADERS.length];
    for (int i = 0; i < rows.size(); i++) {
      data[i] = rows.get(i);
    }
    System.out.println(FlipTable.of(HEADERS, data));
  }
}
