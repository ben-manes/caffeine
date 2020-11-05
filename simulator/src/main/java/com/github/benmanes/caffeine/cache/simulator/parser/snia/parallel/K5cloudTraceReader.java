/*
 * Copyright 2020 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.snia.parallel;

import java.util.stream.LongStream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader.KeyOnlyTraceReader;

/**
 * A reader for the K5cloud trace files provided by
 * <a href="http://iotta.snia.org/tracetypes/4">SNIA</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class K5cloudTraceReader extends TextTraceReader implements KeyOnlyTraceReader {
  static final int BLOCK_SIZE = 512;

  public K5cloudTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public LongStream keys() {
    return lines()
        .map(line -> line.split(","))
        .filter(array -> array[2].charAt(0) == 'R')
        .flatMapToLong(array -> {
          long offset = Long.parseLong(array[3]);
          long startBlock = (offset / BLOCK_SIZE);
          int sequence = Integer.parseInt(array[4]) / BLOCK_SIZE;
          return LongStream.range(startBlock, startBlock + sequence);
    });
  }
}
