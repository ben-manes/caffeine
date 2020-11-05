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
package com.github.benmanes.caffeine.cache.simulator.parser.adapt_size;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;

import java.util.Set;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.google.common.collect.Sets;
import com.google.common.hash.Hashing;

/**
 * A reader for the trace files provided by the authors of the AdaptSize algorithm. See
 * <a href="https://github.com/dasebe/webcachesim#how-to-get-traces">traces</a>.
 * <p>
 * The AdaptSize simulator treats identical keys with different weights as unique entries, rather
 * than as an update to that entry's size. This behavior is emulated by a key hash.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AdaptSizeTraceReader extends TextTraceReader {

  public AdaptSizeTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Set<Characteristic> characteristics() {
    return Sets.immutableEnumSet(WEIGHTED);
  }

  @Override
  public Stream<AccessEvent> events() {
    return lines()
        .map(line -> line.split(" ", 3))
        .map(array -> {
          long key = Long.parseLong(array[1]);
          int weight = Integer.parseInt(array[2]);
          long hashKey = Hashing.murmur3_128().newHasher()
              .putLong(key).putInt(weight).hash().asLong();
          return AccessEvent.forKeyAndWeight(hashKey, weight);
        });
  }
}
