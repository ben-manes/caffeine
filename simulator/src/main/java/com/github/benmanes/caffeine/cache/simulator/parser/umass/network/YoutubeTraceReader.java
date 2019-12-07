/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.umass.network;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.Characteristics;
import com.github.benmanes.caffeine.cache.simulator.parser.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;

/**
 * A reader for the trace files provided by the
 * <a href="http://traces.cs.umass.edu/index.php/Network/Network">UMass Trace Repository</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class YoutubeTraceReader extends TextTraceReader {

  public YoutubeTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Stream<AccessEvent> events() throws IOException {
    return lines()
        .map(line -> line.split(" "))
        .filter(array -> array[3].equals("GETVIDEO"))
        .map(array -> new AccessEvent.AccessEventBuilder(Hashing.murmur3_128().hashUnencodedChars(array[4]).asLong()).build());
  }

  @Override
  public Set<Characteristics> getCharacteristicsSet() {
    return ImmutableSet.of(Characteristics.KEY);
  }
}
