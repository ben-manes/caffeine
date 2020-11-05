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
package com.github.benmanes.caffeine.cache.simulator.parser.camelab;

import java.util.stream.LongStream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader.KeyOnlyTraceReader;

/**
 * A reader for the trace files provided by
 * <a href="https://trace.camelab.org/2016/03/01/flash.html">Camelab</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CamelabTraceReader extends TextTraceReader implements KeyOnlyTraceReader {
  static final long BLOCK_SIZE = 512;

  public CamelabTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public LongStream keys() {
    return lines().flatMapToLong(line -> {
      String[] array = line.split(" ", 5);
      char readWrite = Character.toLowerCase(array[1].charAt(0));
      if (readWrite == 'w') {
        return LongStream.empty();
      }

      long startAddress = Long.parseLong(array[2]);
      int requestSize = Integer.parseInt(array[3]);
      long[] blocks = new long[requestSize];
      for (int i = 0; i < requestSize; i++) {
        blocks[i] = startAddress + (i * BLOCK_SIZE);
      }
      return LongStream.of(blocks);
    });
  }
}
