/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.lirs;

import java.util.stream.LongStream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader.KeyOnlyTraceReader;

/**
 * A reader for the trace files provided by the authors of the LIRS algorithm.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LirsTraceReader extends TextTraceReader implements KeyOnlyTraceReader {

  public LirsTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public LongStream keys() {
    return lines()
        .filter(line -> !line.isEmpty())
        .filter(line -> !line.equals("*"))
        .mapToLong(Long::parseLong);
  }
}
