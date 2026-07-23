/*
 * Copyright 2019 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.snia.cambridge;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.math.RoundingMode;
import java.util.stream.LongStream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader.KeyOnlyTraceReader;
import com.google.common.hash.Hashing;
import com.google.common.math.IntMath;

/**
 * A reader for the SNIA MSR Cambridge trace files provided by
 * <a href="https://iotta.snia.org/traces/388">SNIA</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CambridgeTraceReader extends TextTraceReader implements KeyOnlyTraceReader {
  static final int BLOCK_SIZE = 512;

  public CambridgeTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public LongStream keys() {
    return lines()
        .map(line -> line.split(","))
        .filter(array -> array[3].equals("Read"))
        .flatMapToLong(array -> {
          // disk numbers repeat across hosts, so include the hostname or servers alias each other
          long namespace = Hashing.murmur3_128().newHasher()
              .putString(array[1], UTF_8)
              .putLong(Long.parseLong(array[2]))
              .hash().asLong();
          long startBlock = Long.parseLong(array[4]) / BLOCK_SIZE;
          int sequence = IntMath.divide(Integer.parseInt(array[5]), BLOCK_SIZE, RoundingMode.UP);
          return LongStream.range(startBlock, startBlock + sequence)
              .map(block -> (namespace << 40) | (block & 0xFFFFFFFFFFL));
        });
  }
}
