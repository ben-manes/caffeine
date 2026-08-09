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
package com.github.benmanes.caffeine.cache.simulator.parser.snia.keyvalue;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;

/**
 * The columns are {@code timestamp op key(hex) size [range_start range_end]}, keeping only
 * {@code REST.GET.OBJECT} rows with the object size as the weight.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class ObjectStoreTraceReaderTest {

  @Test
  void zeroByteObjectHasUnitWeight(@TempDir Path dir) throws IOException {
    // Trace 097 contains zero-byte objects (a GET of range 0 -1); an entry still occupies the
    // cache, so its weight floors at one rather than rejecting the event
    var events = read(dir, """
        1219008 REST.PUT.OBJECT 8d4fcda3d675bac9 1056
        1232488 REST.GET.OBJECT 95d363d3fbdc0b03 1168 0 1167
        196535044 REST.GET.OBJECT 8688e0d2d279c9bb 0 0 -1
        """);
    assertThat(events).hasLength(2);
    assertThat(events[0].weight()).isEqualTo(1168);
    assertThat(events[1].weight()).isEqualTo(1);
  }

  @Test
  void sizelessGetIsSkipped(@TempDir Path dir) throws IOException {
    // SNIA documents the size and range columns as optional. A GET of unrecorded length cannot be
    // weighed, so it is dropped; before this the reader indexed past the row and died mid-trace.
    var events = read(dir, """
        1232488 REST.GET.OBJECT 95d363d3fbdc0b03 1168 0 1167
        1232489 REST.GET.OBJECT 8688e0d2d279c9bb
        """);
    assertThat(events).hasLength(1);
    assertThat(events[0].weight()).isEqualTo(1168);
  }

  @Test
  void negativeSizeIsRejected(@TempDir Path dir) {
    // the unit floor exists because AccessEvent requires a positive weight, not to make a corrupt
    // row look like a one-byte object
    assertThrows(IllegalArgumentException.class, () -> read(dir, """
        1232488 REST.GET.OBJECT 95d363d3fbdc0b03 -1168 0 1167
        """));
  }

  private static AccessEvent[] read(Path dir, String content) throws IOException {
    Path trace = Files.writeString(dir.resolve("objectstore.trace"), content);
    return new ObjectStoreTraceReader(trace.toString()).events().toArray(AccessEvent[]::new);
  }
}
