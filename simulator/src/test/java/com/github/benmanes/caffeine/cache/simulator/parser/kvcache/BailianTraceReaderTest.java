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

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An engine reuses a block's KV only when the block's whole prefix matches, so a block is keyed by
 * its prefix rather than by its published identifier, which the To-C and To-B traces reuse after
 * different prefixes.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class BailianTraceReaderTest {

  @Test
  void identifierAfterDifferentPrefixIsDistinct(@TempDir Path dir) throws IOException {
    long[] keys = read(dir, """
        {"chat_id": 0, "parent_chat_id": -1, "hash_ids": [1, 2, 3]}
        {"chat_id": 1, "parent_chat_id": -1, "hash_ids": [4, 2, 3]}
        """);
    assertThat(keys).hasLength(6);
    assertThat(keys[4]).isNotEqualTo(keys[1]);
    assertThat(keys[5]).isNotEqualTo(keys[2]);
  }

  @Test
  void nextTurnRereadsItsPrefix(@TempDir Path dir) throws IOException {
    long[] keys = read(dir, """
        {"chat_id": 0, "parent_chat_id": -1, "hash_ids": [1, 2, 3]}
        {"chat_id": 1, "parent_chat_id": 0, "hash_ids": [1, 2, 3, 4]}
        """);
    assertThat(keys).hasLength(7);
    assertThat(Arrays.copyOfRange(keys, 3, 6)).isEqualTo(Arrays.copyOfRange(keys, 0, 3));
    assertThat(Arrays.copyOfRange(keys, 0, 3)).asList().doesNotContain(keys[6]);
  }

  private static long[] read(Path dir, String content) throws IOException {
    Path trace = Files.writeString(dir.resolve("bailian.jsonl"), content);
    return new BailianTraceReader(trace.toString()).keys().toArray();
  }
}
