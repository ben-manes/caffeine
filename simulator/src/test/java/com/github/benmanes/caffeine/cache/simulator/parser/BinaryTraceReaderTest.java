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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;

/**
 * Verifies that a binary trace whose final record is truncated fails loudly rather than being
 * silently accepted as a clean end-of-trace.
 */
final class BinaryTraceReaderTest {

  @Test
  void completeTrace(@TempDir Path dir) throws IOException {
    long[] keys = read(dir, longs(10, 20));
    assertThat(keys).asList().containsExactly(10L, 20L).inOrder();
  }

  @Test
  void truncatedTrace(@TempDir Path dir) {
    // A full 8-byte record followed by 3 stray bytes (a partial second record)
    byte[] data = ByteBuffer.allocate(Long.BYTES + 3).putLong(10).array();
    var thrown = assertThrows(UncheckedIOException.class, () -> read(dir, data));
    assertThat(thrown).hasMessageThat().contains("Truncated");
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

  private static byte[] longs(long... values) {
    var buffer = ByteBuffer.allocate(values.length * Long.BYTES);
    for (long value : values) {
      buffer.putLong(value);
    }
    return buffer.array();
  }
}
