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
package com.github.benmanes.caffeine.cache.simulator.parser.snia.keyvalue;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;

import java.math.BigInteger;
import java.util.Set;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;

/**
 * A reader for the IBM ObjectStore trace files provided by
 * <a href="http://iotta.snia.org/traces/36305">SNIA</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ObjectStoreTraceReader extends TextTraceReader {

  public ObjectStoreTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Set<Characteristic> characteristics() {
    return Sets.immutableEnumSet(WEIGHTED);
  }

  @Override
  public Stream<AccessEvent> events() {
    return lines()
        .map(line -> line.split(" "))
        .filter(array -> array[1].equals("REST.GET.OBJECT"))
        .map(array -> {
          long key = new BigInteger(array[2], 16).longValue();
          int weight;
          if (array.length == 3) {
            weight = Integer.parseInt(array[3]);
          } else {
            long start = Long.parseLong(array[4]);
            long end = Long.parseLong(array[5]);
            weight = Ints.saturatedCast(end - start);
          }
          return AccessEvent.forKeyAndWeight(key, weight);
        });
  }
}
