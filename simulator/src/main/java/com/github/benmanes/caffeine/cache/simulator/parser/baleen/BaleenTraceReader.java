/*
 * Copyright 2024 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.baleen;

import java.math.RoundingMode;
import java.util.stream.LongStream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader.KeyOnlyTraceReader;
import com.google.common.math.IntMath;

/**
 * A reader for the trace files provided by the authors of the Baleen algorithm. See
 * <a href="https://ftp.pdl.cmu.edu/pub/datasets/Baleen24">traces</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class BaleenTraceReader extends TextTraceReader implements KeyOnlyTraceReader {
  private static final int SEGMENT_SIZE = 128 * 1024;

  public BaleenTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public LongStream keys() {
    return lines()
        .dropWhile(line -> line.startsWith("#"))
        .map(line -> line.split(" ", 6))
        .filter(line -> isRead(line[4].charAt(0)))
        .flatMapToLong(line -> {
          long block = Long.parseLong(line[0]);
          long byteOffset = Long.parseLong(line[1]);
          int size = Integer.parseInt(line[2]);

          int startSegment = Math.toIntExact(byteOffset / SEGMENT_SIZE);
          int sequence = IntMath.divide(size, SEGMENT_SIZE, RoundingMode.UP);

          long startKey = ((long) Long.hashCode(block) << 32) | startSegment;
          return LongStream.range(startKey, startKey + sequence);
        });
  }

  private static boolean isRead(char operation) {
    return (operation == '1') || (operation == '2') || (operation == '5');
  }
}
