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
package com.github.benmanes.caffeine.cache.simulator.parser;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZ;
import org.tukaani.xz.XZOutputStream;

import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;

/**
 * Verifies that a recognized but unreadable container fails loudly. A probe that cannot decode the
 * input rewinds and lets the next one try, so an accepted magic number followed by a decoding
 * failure would otherwise be replayed as raw records and produce a plausible but fabricated result.
 */
final class AbstractTraceReaderTest {

  @Test
  void trace_gzip(@TempDir Path dir) throws IOException {
    assertThat(read(dir, gzip(longs(10, 20)))).asList().containsExactly(10L, 20L).inOrder();
  }

  @Test
  void trace_xz(@TempDir Path dir) throws IOException {
    assertThat(read(dir, xz(longs(10, 20)))).asList().containsExactly(10L, 20L).inOrder();
  }

  @Test
  void trace_xz_corrupt(@TempDir Path dir) {
    var corrupt = ByteBuffer.allocate(16).put(XZ.HEADER_MAGIC).array();
    var thrown = assertThrows(UncheckedIOException.class, () -> read(dir, corrupt));
    assertThat(thrown).hasMessageThat().contains("xz");
  }

  @Test
  void trace_raw(@TempDir Path dir) throws IOException {
    // Shorter than an xz header, so the decoder cannot rule the format out on its own
    assertThat(read(dir, longs(10))).asList().containsExactly(10L);
  }

  private static long[] read(Path dir, byte[] data) throws IOException {
    Path trace = dir.resolve("trace.bin");
    Files.write(trace, data);
    var reader = new BinaryTraceReader(trace.toString()) {
      @Override public Set<Characteristic> characteristics() {
        return Set.of();
      }
      @Override protected AccessEvent readEvent(DataInputStream input) throws IOException {
        return AccessEvent.forKey(input.readLong());
      }
    };
    try (var events = reader.events()) {
      return events.mapToLong(AccessEvent::key).toArray();
    }
  }

  private static byte[] gzip(byte[] data) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var output = new GZIPOutputStream(bytes)) {
      output.write(data);
    }
    return bytes.toByteArray();
  }

  private static byte[] xz(byte[] data) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var output = new XZOutputStream(bytes, new LZMA2Options())) {
      output.write(data);
    }
    return bytes.toByteArray();
  }

  private static byte[] longs(long... values) {
    var buffer = ByteBuffer.allocate(values.length * Long.BYTES);
    for (long value : values) {
      buffer.putLong(value);
    }
    return buffer.array();
  }
}
