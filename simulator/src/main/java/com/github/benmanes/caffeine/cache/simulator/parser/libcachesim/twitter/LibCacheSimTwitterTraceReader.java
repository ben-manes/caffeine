/*
 * Copyright 2023 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.libcachesim.twitter;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;

import java.util.Set;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;

import net.openhft.hashing.LongHashFunction;

/**
 * A reader for the <b>data/twitter_cluster52.csv</b> file provided by the authors of
 * <a href="https://github.com/cacheMon/libCacheSim">libCacheSim</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LibCacheSimTwitterTraceReader extends TextTraceReader {

  public LibCacheSimTwitterTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Set<Characteristic> characteristics() {
    return Set.of(WEIGHTED);
  }

  @Override
  public Stream<AccessEvent> events() {
    var hasher = LongHashFunction.xx3();
    return lines()
        .skip(1)
        .map(line -> line.split(", "))
        .map(array -> {
          long key = hasher.hashChars(array[1]);
          int weight = Integer.parseInt(array[2]);
          return AccessEvent.forKeyAndWeight(key, weight);
        });
  }
}
