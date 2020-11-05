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

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;

import java.util.Set;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.google.common.collect.Sets;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;

/**
 * A reader for the Tencent Photo Cache trace files provided by
 * <a href="http://iotta.snia.org/tracetypes/4">SNIA</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TencentPhotoTraceReader extends TextTraceReader {
  private static final String JPEG_FORMAT = "0";
  private static final String WEBP_FORMAT = "5";

  public TencentPhotoTraceReader(String filePath) {
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
        .filter(array -> array[2].equals(JPEG_FORMAT) || array[2].equals(WEBP_FORMAT))
        .map(array -> {
          long key = Hashing.murmur3_128().hashBytes(
              BaseEncoding.base16().lowerCase().decode(array[1])).asLong();
          return AccessEvent.forKeyAndWeight(key, Integer.parseInt(array[4]));
        });
  }
}
