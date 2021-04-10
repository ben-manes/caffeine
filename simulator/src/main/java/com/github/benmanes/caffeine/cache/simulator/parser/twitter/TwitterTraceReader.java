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
package com.github.benmanes.caffeine.cache.simulator.parser.twitter;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;

import java.util.Set;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.google.common.collect.Sets;
import com.google.common.hash.Hashing;

/**
 * A reader for the trace files provided by Twitter from their in-memory cache clusters. See
 * <a href="https://github.com/twitter/cache-trace">traces</a> for details.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TwitterTraceReader extends TextTraceReader {

  public TwitterTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Set<Characteristic> characteristics() {
    return Sets.immutableEnumSet(WEIGHTED);
  }

  @Override
  public Stream<AccessEvent> events() {
    return lines()
        .map(line -> line.split(","))
        .filter(array -> {
          String operation = array[5];
          return operation.equals("get") || operation.equals("gets");
        }).map(array -> {
          long key = Hashing.murmur3_128().hashUnencodedChars(array[1]).asLong();
          int weight = Integer.parseInt(array[2]) + Integer.parseInt(array[3]);
          return AccessEvent.forKeyAndWeight(key, weight);
        });
  }
}
