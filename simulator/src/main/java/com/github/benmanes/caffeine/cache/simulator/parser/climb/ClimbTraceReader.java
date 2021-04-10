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
package com.github.benmanes.caffeine.cache.simulator.parser.climb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.InputStream;
import java.util.PrimitiveIterator;
import java.util.Scanner;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.LongStream;
import java.util.stream.StreamSupport;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader.KeyOnlyTraceReader;

/**
 * A reader for the trace files provided by the authors of the AdaptiveClimb algorithm.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ClimbTraceReader extends TextTraceReader implements KeyOnlyTraceReader {

  public ClimbTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public LongStream keys() {
    TraceIterator iterator = new TraceIterator(readFile());
    return StreamSupport.longStream(Spliterators.spliteratorUnknownSize(
        iterator, Spliterator.ORDERED), /* parallel */ false).onClose(iterator::close);
  }

  private static final class TraceIterator implements PrimitiveIterator.OfLong {
    private final Scanner scanner;

    TraceIterator(InputStream input) {
      scanner = new Scanner(input, UTF_8.name());
    }

    @Override
    public boolean hasNext() {
      return scanner.hasNextLong();
    }

    @Override
    public long nextLong() {
      return scanner.nextLong();
    }

    public void close() {
      scanner.close();
    }
  }
}
