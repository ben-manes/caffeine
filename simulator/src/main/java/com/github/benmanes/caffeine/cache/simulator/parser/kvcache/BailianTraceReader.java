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
import com.google.errorprone.annotations.Var;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.openhft.hashing.LongHashFunction;

/**
 * A reader for the trace files provided by the authors of the Qwen Bailian KV prefix cache study.
 * See <a href="https://github.com/alibaba-edu/qwen-bailian-usagetraces-anon">traces</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class BailianTraceReader extends TextTraceReader implements KeyOnlyTraceReader {
  private static final LongHashFunction HASH = LongHashFunction.xx3();
  private static final JsonFactory JSON = new JsonFactory();

  public BailianTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public LongStream keys() {
    return lines()
        .filter(not(String::isEmpty))
        .flatMapToLong(BailianTraceReader::blocks);
  }

  /** Returns the request's blocks in prefix order, each keyed by the prefix that it ends. */
  private static LongStream blocks(String line) {
    // Each line is a request whose hash_ids field is the 16-token blocks of its prompt, ordered
    // from the start of the context. An identifier is not always unique to its prefix, as the To-C
    // and To-B traces reuse one after different prefixes, but an engine can reuse a block's KV
    // only when the whole prefix matches. A block's key therefore chains its identifier onto the
    // key of the block before it.
    try (var parser = JSON.createParser(line)) {
      while (parser.nextToken() != null) {
        if ((parser.currentToken() == JsonToken.FIELD_NAME)
            && Objects.equals(parser.currentName(), "hash_ids")) {
          parser.nextToken();
          @Var long key = 0;
          var keys = new LongArrayList();
          while (parser.nextToken() != JsonToken.END_ARRAY) {
            key = HASH.hashLongs(new long[] {key, parser.getLongValue()});
            keys.add(key);
          }
          return keys.longStream();
        }
      }
      throw new IllegalArgumentException("No hash_ids in request: " + line);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
