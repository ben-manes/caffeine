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
package com.github.benmanes.caffeine.cache.simulator.parser.snia.cambridge;

import java.io.IOException;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.event.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;

/**
 * A reader for the SNIA MSR Cambridge trace files provided by
 * <a href="http://iotta.snia.org/traces/388">SNIA</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CambridgeTraceReader extends TextTraceReader {

  public CambridgeTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Stream<AccessEvent> events() throws IOException {
    return lines()
        .map(line -> line.split(",", 6))
        .map(array -> new AccessEvent(Long.parseLong(array[4])));
  }
}
