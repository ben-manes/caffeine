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
package com.github.benmanes.caffeine.cache.simulator.parser.snia.parallel;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;

/**
 * A photo is stored as one blob per (id, format, size category), so those variants are the object's
 * identity; the returned bytes are its weight, not part of the key. The columns are
 * {@code timestamp id format(0=jpg,5=webp) size(l,a,o,m,c,b) return_size ...}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class TencentPhotoTraceReaderTest {

  @Test
  void distinguishesSizeCategories(@TempDir Path dir) throws IOException {
    // Same photo and format but a thumbnail vs a full size are distinct blobs, even at equal bytes
    var events = read(dir, "1 abcd 0 l 5000 1 PHONE 0\n1 abcd 0 b 5000 1 PHONE 0\n");
    assertThat(events).hasLength(2);
    assertThat(events[0].key()).isNotEqualTo(events[1].key());
  }

  @Test
  void distinguishesFormats(@TempDir Path dir) throws IOException {
    // Same photo and size but jpg vs webp are distinct blobs, even at equal bytes
    var events = read(dir, "1 abcd 0 m 5000 1 PHONE 0\n1 abcd 5 m 5000 1 PHONE 0\n");
    assertThat(events).hasLength(2);
    assertThat(events[0].key()).isNotEqualTo(events[1].key());
  }

  @Test
  void oneVariantIsOneObjectDespiteVaryingReturnSize(@TempDir Path dir) throws IOException {
    // The same variant reporting different returned bytes is one cached object, not two
    var events = read(dir, "1 abcd 0 m 5000 1 PHONE 0\n1 abcd 0 m 6000 1 PHONE 0\n");
    assertThat(events).hasLength(2);
    assertThat(events[0].key()).isEqualTo(events[1].key());
    assertThat(events[0].weight()).isEqualTo(5000);
    assertThat(events[1].weight()).isEqualTo(6000);
  }

  private static AccessEvent[] read(Path dir, String content) throws IOException {
    Path trace = Files.writeString(dir.resolve("photo.trace"), content);
    return new TencentPhotoTraceReader(trace.toString()).events().toArray(AccessEvent[]::new);
  }
}
