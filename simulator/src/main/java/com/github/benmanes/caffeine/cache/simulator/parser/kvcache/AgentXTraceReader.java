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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Comparator.comparingDouble;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import org.apache.commons.lang3.mutable.MutableLong;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader.KeyOnlyTraceReader;
import com.google.errorprone.annotations.Var;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.openhft.hashing.LongHashFunction;

/**
 * A reader for the trace files provided by the authors of the SemiAnalysis cross-request KV prefix
 * cache. See <a href="https://huggingface.co/datasets/semianalysisai">cc-traces</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AgentXTraceReader extends TextTraceReader implements KeyOnlyTraceReader {
  /*
   * A session's block identifiers are dense and private to it, including those of the subagents it
   * spawns, which are nested inside the turn that spawned them and share the session's identifiers.
   * The reader flattens the subagents into their session and, where the corpus declares its
   * identifiers session-local, namespaces them by the session so that sessions share nothing. A
   * corpus declaring them global already names the same block by the same identifier everywhere, so
   * namespacing one would delete the sharing it is published to show. The identifier is
   * then hashed, because a key built by packing two fields into a long is not safe to hand to a
   * policy that hashes it: Long.hashCode folds the halves together, which maps 13 of these blocks
   * onto each hash and leaves the frequency sketch measuring noise while every policy that keys an
   * exact map is unaffected.
   *
   * The corpus timestamps a request against its own session's start and does not say when the
   * sessions ran, so their arrival order is synthesized by spreading them uniformly at random over
   * a window. The window is the trace's capacity knob: it decides how many sessions are live at
   * once and therefore how much of the cache each one is competing for. Replaying the sessions back
   * to back would hand the whole cache to each in turn and measure nothing. The value below is the
   * upstream study's default.
   */
  private static final double ARRIVAL_WINDOW = TimeUnit.HOURS.toSeconds(2);
  private static final LongHashFunction HASH = LongHashFunction.xx3();
  private static final JsonFactory JSON = new JsonFactory();
  private static final String GLOBAL_SCOPE = "global";
  private static final String LOCAL_SCOPE = "local";
  private static final long SEED = 0;

  public AgentXTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public LongStream keys() {
    return requests().stream().flatMapToLong(request -> expand(request.runs));
  }

  /** Returns every session's requests, ordered by their synthesized arrival time. */
  private List<Request> requests() {
    var requests = new ArrayList<Request>();
    var session = new MutableLong();
    var random = new Random(SEED);
    try (var lines = lines()) {
      lines.forEach(line -> parseLine(session.incrementAndGet(), line, requests, random));
    }
    requests.sort(comparingDouble(request -> request.time));
    return requests;
  }

  /**
   * Each line is a session: a series of requests, each naming the 64-token blocks of its prompt in
   * prefix order. A turn rescans the blocks of the turns before it and appends the few that its new
   * content produced, so a session is a slowly growing cyclic scan and the trace is those scans
   * interleaved.
   */
  private static void parseLine(long session, String line, List<Request> requests, Random random) {
    var chains = new ArrayList<Request>();
    String scope = parseSession(line, chains);
    if (chains.isEmpty()) {
      return;
    }
    double start = random.nextDouble(ARRIVAL_WINDOW);
    long namespace = scope.equals(LOCAL_SCOPE) ? (session << Integer.SIZE) : 0;
    for (var chain : chains) {
      for (int i = 0; i < chain.runs.length; i += 2) {
        chain.runs[i] |= namespace;
      }
      requests.add(new Request(start + chain.time, chain.runs));
    }
  }

  /** Adds the session's requests and returns the scope that its block identifiers are named in. */
  private static String parseSession(String line, List<Request> chains) {
    @Var String scope = LOCAL_SCOPE;
    try (JsonParser parser = JSON.createParser(line)) {
      checkState(parser.nextToken() == JsonToken.START_OBJECT, "Not a session: %s", line);
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        String field = parser.currentName();
        parser.nextToken();
        switch (field) {
          case "hash_id_scope" -> scope = parser.getText();
          case "requests" -> parseRequests(parser, chains);
          default -> parser.skipChildren();
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    checkState(scope.equals(LOCAL_SCOPE) || scope.equals(GLOBAL_SCOPE),
        "Unknown hash_id_scope: %s", scope);
    return scope;
  }

  /** Adds each request of the array that the parser is positioned on. */
  private static void parseRequests(JsonParser parser, Collection<Request> chains)
      throws IOException {
    if (parser.currentToken() != JsonToken.START_ARRAY) {
      parser.skipChildren();
      return;
    }
    while (parser.nextToken() != JsonToken.END_ARRAY) {
      parseRequest(parser, chains);
    }
  }

  /** Adds the request that the parser is positioned on, or the subagent's requests if it is one. */
  private static void parseRequest(JsonParser parser, Collection<Request> chains)
      throws IOException {
    var subagent = new ArrayList<Request>();
    @Var boolean isSubagent = false;
    @Var long[] runs = {};
    @Var double time = 0;

    while (parser.nextToken() != JsonToken.END_OBJECT) {
      String field = parser.currentName();
      parser.nextToken();
      switch (field) {
        case "type" -> isSubagent = Objects.equals(parser.getText(), "subagent");
        case "requests" -> parseRequests(parser, subagent);
        case "hash_ids" -> runs = parseBlocks(parser);
        case "t" -> time = parser.getDoubleValue();
        default -> parser.skipChildren();
      }
    }

    if (isSubagent) {
      chains.addAll(subagent);
    } else if (runs.length != 0) {
      chains.add(new Request(time, runs));
    }
  }

  /**
   * Returns the blocks of the array that the parser is positioned on, as (first, length) pairs of
   * the consecutive runs they form. A turn's chain is a few long runs, so this holds the corpus in
   * a hundredth of the memory that its 108M block references would need.
   */
  private static long[] parseBlocks(JsonParser parser) throws IOException {
    var runs = new LongArrayList();
    @Var long length = 0;
    @Var long first = 0;

    while (parser.nextToken() != JsonToken.END_ARRAY) {
      long block = parser.getLongValue();
      if (block == (first + length)) {
        length++;
        continue;
      }
      if (length != 0) {
        runs.add(first);
        runs.add(length);
      }
      first = block;
      length = 1;
    }
    if (length != 0) {
      runs.add(first);
      runs.add(length);
    }
    return runs.toLongArray();
  }

  /** Returns the hashed blocks that the runs encode. */
  private static LongStream expand(long[] runs) {
    return IntStream.range(0, runs.length / 2).boxed()
        .flatMapToLong(i -> LongStream.range(runs[2 * i], runs[2 * i] + runs[(2 * i) + 1]))
        .map(HASH::hashLong);
  }

  /** A request's arrival time and the runs of the block chain that it scans. */
  @SuppressWarnings("ArrayRecordComponent")
  private record Request(double time, long[] runs) {}
}
