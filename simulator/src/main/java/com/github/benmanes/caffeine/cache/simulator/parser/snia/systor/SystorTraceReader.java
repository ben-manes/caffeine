/*
 * Copyright 2021 Omri Himelbrand. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.snia.systor;

import java.math.RoundingMode;
import java.util.Set;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.google.common.math.IntMath;

/**
 * A reader for the SNIA SYSTOR '17 trace files provided by
 * <a href="http://iotta.snia.org/traces/4964">SNIA</a>. This reader uses the response time as the
 * miss penalty (converted to milliseconds).
 *
 * @author himelbrand@gmail.com (Omri Himelbrand)
 */
public final class SystorTraceReader extends TextTraceReader {
  static final int BLOCK_SIZE = 512;

  public SystorTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Set<Characteristic> characteristics() {
    return Set.of();
  }

  @Override
  public Stream<AccessEvent> events() {
    return lines()
        .map(line -> line.split(","))
        .filter(array -> array.length == 6)
        .filter(array -> !array[1].isEmpty())
        .filter(array -> array[2].equals("R"))
        .flatMap(array -> {
          int size = Integer.parseInt(array[5]);
          long offset = Long.parseLong(array[4]);
          double responseTime = 1000 * Double.parseDouble(array[1]);
          int sequence = IntMath.divide(size, BLOCK_SIZE, RoundingMode.UP);
          return LongStream.range(offset, offset + sequence)
              .mapToObj(key -> AccessEvent.forKeyAndPenalties(key, 0, responseTime));
        });
  }
}
