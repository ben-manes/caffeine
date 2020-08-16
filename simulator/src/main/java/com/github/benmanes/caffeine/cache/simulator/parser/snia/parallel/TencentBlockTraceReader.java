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

import java.io.IOException;
import java.util.List;
import java.util.stream.LongStream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader.KeyOnlyTraceReader;
import com.google.common.base.Splitter;

/**
 * A reader for the Tencent Block Storage trace files provided by
 * <a href="http://iotta.snia.org/tracetypes/4">SNIA</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TencentBlockTraceReader extends TextTraceReader implements KeyOnlyTraceReader {
  static final Splitter splitter = Splitter.on(',');
  static final int BLOCK_SIZE = 512;
  static final char WRITE = '1';

  public TencentBlockTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public LongStream keys() throws IOException {
    return lines().flatMapToLong(line -> {
      List<String> list = splitter.splitToList(line);
      if (list.get(3).charAt(0) == WRITE) {
        return LongStream.empty();
      }
      long offset = Long.parseLong(list.get(1));
      long startBlock = (offset / BLOCK_SIZE);
      int sequence = Integer.parseInt(list.get(2));
      return LongStream.range(startBlock, startBlock + sequence);
    });
  }
}
