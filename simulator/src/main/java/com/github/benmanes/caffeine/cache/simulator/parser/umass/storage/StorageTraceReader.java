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
package com.github.benmanes.caffeine.cache.simulator.parser.umass.storage;

import java.math.RoundingMode;
import java.util.stream.LongStream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader.KeyOnlyTraceReader;
import com.google.common.math.IntMath;

/**
 * A reader for the trace files provided by the
 * <a href="http://traces.cs.umass.edu/index.php/Storage/Storage">UMass Trace Repository</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class StorageTraceReader extends TextTraceReader implements KeyOnlyTraceReader {
  static final int BLOCK_SIZE = 512;

  public StorageTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public LongStream keys() {
    return lines().flatMapToLong(line -> {
      String[] array = line.split(",", 5);
      if (array.length <= 4) {
        return LongStream.empty();
      }
      long startBlock = Long.parseLong(array[1]);
      int size = Integer.parseInt(array[2]);
      int sequence = IntMath.divide(size, BLOCK_SIZE, RoundingMode.UP);
      char readWrite = Character.toLowerCase(array[3].charAt(0));
      return (readWrite == 'w')
          ? LongStream.empty()
          : LongStream.range(startBlock, startBlock + sequence);
    });
  }
}
