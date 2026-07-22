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
package com.github.benmanes.caffeine.cache.simulator.parser.baleen;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the reader mirrors the authors' reference (BCacheSim): a request spans every 128 KiB
 * segment its byte range touches, the shard is part of the key, and a request is replayed by its
 * op_count. Fidelity to the reference on the full traces is validated ad hoc (bit-for-bit key
 * sequence); these guard the individual behaviors.
 */
final class BaleenTraceReaderTest {
  private static final int SEGMENT = 128 * 1024;

  @Test
  void unalignedRequestSpansTrailingSegment(@TempDir Path dir) throws IOException {
    // Bytes [100 KiB, 228 KiB) touch segment 0 and segment 1
    assertThat(read(dir, "10 " + (100 * 1024) + " " + SEGMENT + " 1000 2 0 0 0\n")).hasLength(2);
  }

  @Test
  void alignedRequestSpansExactSegments(@TempDir Path dir) throws IOException {
    assertThat(read(dir, "10 0 " + (2 * SEGMENT) + " 1000 2 0 0 0\n")).hasLength(2);
  }

  @Test
  void opCountReplaysTheRequest(@TempDir Path dir) throws IOException {
    // A non-pipeline (10-column) row with op_count 3 replays its single-segment request three times
    long[] keys = read(dir, "10 0 " + SEGMENT + " 1000 2 0 0 7 3 0\n");
    assertThat(keys).hasLength(3);
    assertThat(Arrays.stream(keys).distinct().count()).isEqualTo(1);
  }

  @Test
  void shardIsPartOfTheKey(@TempDir Path dir) throws IOException {
    long[] keys = read(dir, "10 0 " + SEGMENT + " 1000 2 0 0 7 1 0\n"
                          + "10 0 " + SEGMENT + " 1001 2 0 0 8 1 0\n");
    assertThat(keys).hasLength(2);
    assertThat(keys[0]).isNotEqualTo(keys[1]);
  }

  @Test
  void nonReadsAndZeroSizedRequestsAreSkipped(@TempDir Path dir) throws IOException {
    long[] keys = read(dir, "10 0 " + SEGMENT + " 1000 3 0 0 0\n"   // op 3 is a write
                          + "11 0 0 1001 2 0 0 0\n"                 // zero-sized
                          + "12 0 " + SEGMENT + " 1002 2 0 0 0\n"); // a valid read
    assertThat(keys).hasLength(1);
  }

  private static long[] read(Path dir, String content) throws IOException {
    Path trace = dir.resolve("trace.trace");
    Files.writeString(trace, content);
    try (var keys = new BaleenTraceReader(trace.toString()).keys()) {
      return keys.toArray();
    }
  }
}
