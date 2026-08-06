/*
 * Copyright 2026 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.thesios;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;

import java.util.Set;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Ints;

/**
 * A reader for the Google <a href="https://github.com/google-research-datasets/thesios">Thesios</a>
 * storage server I/O traces.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ThesiosTraceReader extends TextTraceReader {

  public ThesiosTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Set<Characteristic> characteristics() {
    return Set.of(WEIGHTED);
  }

  @Override
  public Stream<AccessEvent> events() {
    // READ rows with the (file, offset) range as the object and the requested bytes as its weight
    return lines()
        .filter(line -> !line.startsWith("filename,"))
        .map(line -> line.split(","))
        .filter(array -> array[6].equals("READ"))
        .map(array -> {
          long key = Hashing.murmur3_128().newHasher()
              .putUnencodedChars(array[0])
              .putLong(Long.parseLong(array[1]))
              .hash().asLong();
          long size = Long.parseLong(array[10]);
          int weight = Math.max(Ints.saturatedCast(size), 1);
          return AccessEvent.forKeyAndWeight(key, weight);
        });
  }
}
