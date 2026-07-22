/*
 * Copyright 2024 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.baleen;

import static java.lang.Math.toIntExact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Gatherer;
import java.util.stream.LongStream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader.KeyOnlyTraceReader;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;

/**
 * A reader for the trace files provided by the authors of the Baleen algorithm. See
 * <a href="https://ftp.pdl.cmu.edu/pub/datasets/Baleen24">traces</a> and the reference
 * <a href="https://github.com/wonglkd/BCacheSim">simulator</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class BaleenTraceReader extends TextTraceReader implements KeyOnlyTraceReader {
  private static final ImmutableSet<String> READ_OPERATIONS = ImmutableSet.of("1", "2", "5");
  private static final Splitter SPLITTER = Splitter.on(' ');
  private static final int SEGMENT_SIZE = 128 * 1024;

  public BaleenTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public LongStream keys() {
    var window = Gatherer.<String, Window, long[]>ofSequential(
        Window::new, Window::accept, Window::flush);
    return lines().gather(window).flatMapToLong(LongStream::of);
  }

  /**
   * Accumulates the requests of a single second, then emits their segment keys in the reference's
   * global timestamp order: each request's {@code op_count} replays are spread across {@code [time,
   * time + 1]} and ordered by that timestamp, with ties keeping the original (file then replay)
   * order. Since the trace's timestamps are non-decreasing, buffering one second at a time is
   * sufficient and a later second's replays never precede an earlier second's.
   */
  private static final class Window {
    private final List<Request> requests;

    private boolean withPipeline;
    private boolean formatKnown;
    private boolean started;
    private long second;

    Window() {
      requests = new ArrayList<>();
    }

    boolean accept(String line, Gatherer.Downstream<? super long[]> downstream) {
      if (line.isEmpty()) {
        return true;
      } else if (line.charAt(0) == '#') {
        if (!formatKnown && line.contains("op_name")) {
          withPipeline = line.contains("pipeline");
          formatKnown = true;
        }
        return true;
      }

      List<String> parts = SPLITTER.splitToList(line);
      if (!formatKnown) {
        // A no header trace is the older layout; otherwise the header was inspected above
        withPipeline = (parts.size() <= 8);
        formatKnown = true;
      }
      if (!READ_OPERATIONS.contains(parts.get(4))) {
        return true;
      }

      long block = Long.parseLong(parts.getFirst());
      long offset = Long.parseLong(parts.get(1));
      int size = Integer.parseInt(parts.get(2));
      if (size == 0) {
        // the reference rejects zero-sized requests
        return true;
      }
      double time = Double.parseDouble(parts.get(3));
      int opCount = (parts.size() >= 9) ? Integer.parseInt(parts.get(8)) : 1;

      long shard = (!withPipeline && (parts.size() > 7)) ? Long.parseLong(parts.get(7)) : 0;
      long base = Hashing.murmur3_128().newHasher().putLong(block).putLong(shard).hash().asLong();
      int startSegment = toIntExact(offset / SEGMENT_SIZE);
      int endSegment = toIntExact((offset + size - 1) / SEGMENT_SIZE);
      long[] segments = LongStream.rangeClosed(startSegment, endSegment)
          .map(segment -> base + segment).toArray();

      if (started && ((long) Math.floor(time) != second)) {
        flush(downstream);
      }
      started = true;
      second = (long) Math.floor(time);
      requests.add(new Request(segments, time, opCount));
      return true;
    }

    void flush(Gatherer.Downstream<? super long[]> downstream) {
      var accesses = new ArrayList<Access>(requests.size());
      for (var request : requests) {
        double interval = (request.opCount == 1) ? 0 : 1.0 / (request.opCount - 1);
        for (int i = 0; i < request.opCount; i++) {
          accesses.add(new Access(request.segments, request.time + (i * interval)));
        }
      }

      // A stable sort keeps the file-then-replay order for the requests sharing a timestamp
      accesses.sort(Comparator.comparingDouble(access -> access.time));
      for (var access : accesses) {
        downstream.push(access.segments);
      }
      requests.clear();
    }

    /** A request expanded to the keys of the segments it spans, plus its replay metadata. */
    @SuppressWarnings("ArrayRecordComponent")
    private record Request(long[] segments, double time, int opCount) {}

    @SuppressWarnings("ArrayRecordComponent")
    private record Access(long[] segments, double time) {}
  }
}
