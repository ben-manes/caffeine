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

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Generate integers according to a histogram distribution. The histogram buckets are of width one,
 * but the values are multiplied by a block size. Therefore, instead of drawing sizes uniformly at
 * random within each bucket, we always draw the largest value in the current bucket, so the value
 * drawn is always a multiple of block_size.
 *
 * The minimum value this distribution returns is block_size (not zero).
 *
 * Modified Nov 19 2010 by sears
 *
 * @author snjones
 * @author https://github.com/brianfrankcooper/YCSB
 */
public class HistogramGenerator extends IntegerGenerator {

  long block_size;
  long[] buckets;
  long area;
  long weighted_area;
  double mean_size;

  @SuppressWarnings("resource")
  public HistogramGenerator(String histogramfile) throws IOException {
    BufferedReader in = new BufferedReader(new FileReader(histogramfile));
    String str;
    String[] line;

    ArrayList<Integer> a = new ArrayList<Integer>();

    str = in.readLine();
    if (str == null) {
      throw new IOException("Empty input file!\n");
    }
    line = str.split("\t");
    if (line[0].compareTo("BlockSize") != 0) {
      throw new IOException("First line of histogram is not the BlockSize!\n");
    }
    block_size = Integer.parseInt(line[1]);

    while ((str = in.readLine()) != null) {
      // [0] is the bucket, [1] is the value
      line = str.split("\t");

      a.add(Integer.parseInt(line[0]), Integer.parseInt(line[1]));
    }
    buckets = new long[a.size()];
    for (int i = 0; i < a.size(); i++) {
      buckets[i] = a.get(i);
    }

    in.close();
    init();
  }

  @SuppressWarnings("PMD.ArrayIsStoredDirectly")
  public HistogramGenerator(long[] buckets, int block_size) {
    this.block_size = block_size;
    this.buckets = buckets;
    init();
  }

  private void init() {
    for (int i = 0; i < buckets.length; i++) {
      area += buckets[i];
      weighted_area = i * buckets[i];
    }
    // calculate average file size
    mean_size = ((double) block_size) * ((double) weighted_area) / (area);
  }

  @Override
  public int nextInt() {
    int number = Utils.random().nextInt((int) area);
    int i;

    for (i = 0; i < (buckets.length - 1); i++) {
      number -= buckets[i];
      if (number <= 0) {
        return (int) ((i + 1) * block_size);
      }
    }

    return (int) (i * block_size);
  }

  @Override
  public double mean() {
    return mean_size;
  }
}
