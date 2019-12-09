/*
 * Copyright 2017 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.penalties.gradle;

import com.github.benmanes.caffeine.cache.simulator.Characteristics;
import com.github.benmanes.caffeine.cache.simulator.parser.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A reader for the Gradle Build Cache trace files provided by the Gradle team, but with an additional field.
 *
 * Each line in the trace has the following format: uuid hit-penalty miss-penalty
 * For example: d05fdfe4022d6da89116ccbd91a8df8b 50 200
 *
 * @author himelbrand@gmail.com (Omri Himelbrand)
 */
public final class GradlePenaltiesTraceReader extends TextTraceReader {

  public GradlePenaltiesTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Stream<AccessEvent> events() throws IOException {
    return lines()
        .map(line -> line.split(" ",3))
        .map(split -> new BigInteger[] {new BigInteger(split[0], 16), new BigInteger(split[1]), new BigInteger(split[2])})
        .map(nums -> new AccessEvent.AccessEventBuilder(nums[0].shiftRight(64).longValue() ^ nums[0].longValue())
                                    .setHitPenalty(nums[1].longValue())
                                    .setMissPenalty(nums[2].longValue()).build());
  }

  @Override
  public Set<Characteristics> getCharacteristicsSet() {
    return ImmutableSet.of(Characteristics.KEY,Characteristics.MISS_PENALTY,Characteristics.HIT_PENALTY);
  }
}
