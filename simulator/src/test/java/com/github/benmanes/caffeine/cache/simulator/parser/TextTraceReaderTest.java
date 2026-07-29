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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.benmanes.caffeine.cache.simulator.parser.lirs.LirsTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.snia.cambridge.CambridgeTraceReader;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class TextTraceReaderTest {

  @Test
  @SuppressWarnings("Varifier")
  void failsOnMalformedInput(@TempDir Path dir) throws IOException {
    // A reader that derives its key from a text field (here the MSR hostname) would decode two
    // distinct malformed byte sequences to the same replacement character and silently alias their
    // keys, so a trace that is not valid UTF-8 must fail rather than be guessed at
    byte c1 = (byte) 0xC3;
    byte c2 = (byte) 0xC4;
    Path file = Files.write(dir.resolve("msr.csv"), new byte[] {
        '0', ',', c1, ',', '0', ',', 'R', 'e', 'a', 'd', ',', '0', ',', '5', '1', '2', '\n',
        '0', ',', c2, ',', '0', ',', 'R', 'e', 'a', 'd', ',', '0', ',', '5', '1', '2', '\n'});

    var error = assertThrows(UncheckedIOException.class,
        () -> new CambridgeTraceReader(file.toString()).keys().count());
    assertThat(error).hasCauseThat().isInstanceOf(MalformedInputException.class);
  }

  @Test
  void readsValidInput(@TempDir Path dir) throws IOException {
    Path file = Files.writeString(dir.resolve("trace.txt"), "1\n2\n");
    assertThat(new LirsTraceReader(file.toString()).keys().toArray()).asList()
        .containsExactly(1L, 2L).inOrder();
  }
}
