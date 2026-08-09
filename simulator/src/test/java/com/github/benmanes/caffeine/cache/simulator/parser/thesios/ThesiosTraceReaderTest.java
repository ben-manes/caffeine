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
package com.github.benmanes.caffeine.cache.simulator.parser.thesios;

import static com.google.common.truth.Truth.assertThat;
import static java.util.Locale.US;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * The published files are shard concatenations, so the header row recurs mid-stream; only READ
 * rows are cache accesses, and a zero-byte read still occupies an entry.
 * <p>
 * {@link #HEADER} is the real header of a published shard, not a restatement of what the reader
 * expects, so the column order below is an oracle rather than a copy of the implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class ThesiosTraceReaderTest {
  static final String HEADER = """
      filename,file_offset,application,c_time,io_zone,redundancy_type,\
      op_type,service_class,from_flash_cache,cache_hit,request_io_size_bytes,\
      disk_io_size_bytes,response_io_size_bytes,start_time,disk_time,\
      simulated_disk_start_time,simulated_latency""";

  @Test
  @SuppressFBWarnings("VA_FORMAT_STRING_USES_NEWLINE")
  void skipsRecurringHeadersAndWrites(@TempDir Path dir) throws IOException {
    var events = read(dir, String.format(US, """
        %s
        aa,0,app,1,COLD,ERASURE_CODED,READ,OTHER,0,0,1050624,1052672,1050624,1.0,0.03,1.0,0.03
        %s
        bb,461618,app,1,WARM,REPLICATED,WRITE,LATENCY_SENSITIVE,0,-1,0,0,0,1.0,0.0,0.0,0.0
        aa,1050624,app,1,COLD,ERASURE_CODED,READ,OTHER,0,0,0,0,0,1.0,0.02,1.0,0.06
        """, HEADER, HEADER));
    assertThat(events).hasLength(2);
    assertThat(events[0].weight()).isEqualTo(1_050_624);
    assertThat(events[1].weight()).isEqualTo(1);
    assertThat(events[0].key()).isNotEqualTo(events[1].key());
  }

  @Test
  void sameRangeIsOneObject(@TempDir Path dir) throws IOException {
    var events = read(dir, """
        aa,0,app,1,COLD,ERASURE_CODED,READ,OTHER,0,0,1050624,0,0,1.0,0.0,0.0,0.0
        aa,0,app,2,COLD,ERASURE_CODED,READ,OTHER,0,1,1050624,0,0,2.0,0.0,0.0,0.0
        """);
    assertThat(events).hasLength(2);
    assertThat(events[0].key()).isEqualTo(events[1].key());
  }

  @Test
  void distinctRangesAreDistinctObjects(@TempDir Path dir) throws IOException {
    // the file name and offset must be framed, or their concatenations collide
    var events = read(dir, """
        aa1,24,app,1,COLD,ERASURE_CODED,READ,OTHER,0,0,1024,0,0,1.0,0.0,0.0,0.0
        aa,124,app,1,COLD,ERASURE_CODED,READ,OTHER,0,0,1024,0,0,1.0,0.0,0.0,0.0
        """);
    assertThat(events).hasLength(2);
    assertThat(events[0].key()).isNotEqualTo(events[1].key());
  }

  @Test
  void corruptRowFailsLoudly(@TempDir Path dir) {
    // a malformed row must fail the run rather than silently skew the result
    assertThrows(NumberFormatException.class, () -> read(dir, """
        aa,not-a-number,app,1,COLD,ERASURE_CODED,READ,OTHER,0,0,1024,0,0,1.0,0.0,0.0,0.0
        """));
  }

  @Test
  void negativeSizeIsRejected(@TempDir Path dir) {
    // the unit floor exists because AccessEvent requires a positive weight, not to make a corrupt
    // row look like a one-byte read
    assertThrows(IllegalArgumentException.class, () -> read(dir, """
        aa,0,app,1,COLD,ERASURE_CODED,READ,OTHER,0,0,-1024,0,0,1.0,0.0,0.0,0.0
        """));
  }

  private static AccessEvent[] read(Path dir, String content) throws IOException {
    Path trace = Files.writeString(dir.resolve("thesios.trace"), content);
    return new ThesiosTraceReader(trace.toString()).events().toArray(AccessEvent[]::new);
  }
}
