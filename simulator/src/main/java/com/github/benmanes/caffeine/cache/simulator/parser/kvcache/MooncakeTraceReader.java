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
package com.github.benmanes.caffeine.cache.simulator.parser.kvcache;

import static java.util.function.Predicate.not;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.stream.LongStream;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader.KeyOnlyTraceReader;

import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * A reader for the trace files provided by the authors of the Mooncake cross-request KV prefix
 * cache. See <a href="https://github.com/kvcache-ai/Mooncake/tree/main/FAST25-release">traces</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class MooncakeTraceReader extends TextTraceReader implements KeyOnlyTraceReader {
  private static final JsonFactory JSON = new JsonFactory();

  public MooncakeTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public LongStream keys() {
    return lines()
        .filter(not(String::isEmpty))
        .flatMapToLong(MooncakeTraceReader::blocks);
  }

  /** Returns the request's blocks in prefix order. */
  private static LongStream blocks(String line) {
    // Each line is a request whose hash_ids field is the 512-token blocks of its prompt, ordered
    // from the start of the context and hashed over the prefix that precedes them, so a block is
    // the cached unit and the request is a scan of its chain. The identifiers are globally scoped,
    // making sharing between requests explicit in the trace.
    try (var parser = JSON.createParser(line)) {
      while (parser.nextToken() != null) {
        if ((parser.currentToken() == JsonToken.FIELD_NAME)
            && Objects.equals(parser.currentName(), "hash_ids")) {
          parser.nextToken();
          var ids = new LongArrayList();
          while (parser.nextToken() != JsonToken.END_ARRAY) {
            ids.add(parser.getLongValue());
          }
          return ids.longStream();
        }
      }
      throw new IllegalArgumentException("No hash_ids in request: " + line);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
