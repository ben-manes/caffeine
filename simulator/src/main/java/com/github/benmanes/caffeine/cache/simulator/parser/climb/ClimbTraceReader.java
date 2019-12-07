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

import java.io.*;
import java.util.*;
import java.util.function.LongConsumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.github.benmanes.caffeine.cache.simulator.parser.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;

/**
 * A reader for the trace files provided by the authors of the AdaptiveClimb algorithm.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ClimbTraceReader extends TextTraceReader {

  public ClimbTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Stream<AccessEvent> events() throws IOException {
    TraceIterator iterator = new TraceIterator(readFile());
    return StreamSupport.stream(Spliterators.spliteratorUnknownSize(
        iterator, Spliterator.ORDERED), /* parallel */ false).onClose(iterator::close);
  }


  private static final class TraceIterator implements PrimitiveIterator<AccessEvent, LongConsumer> {
    private final Scanner scanner;
    long nextKey;

    TraceIterator(InputStream input) {
      scanner = new Scanner(input, UTF_8.name());
    }


    @Override
    public boolean hasNext() {
      return scanner.hasNextLong();
    }

    @Override
    public AccessEvent next() {
      return new AccessEvent.AccessEventBuilder(nextKey).build();
    }

    public long nextLong() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      nextKey = scanner.nextLong();
      return nextKey;
    }

    @Override
    public void forEachRemaining(LongConsumer longConsumer) {
      Objects.requireNonNull(longConsumer);

      while(this.hasNext()) {
        longConsumer.accept(this.nextLong());
      }
    }

    public void close() {
      scanner.close();
    }
  }
}



