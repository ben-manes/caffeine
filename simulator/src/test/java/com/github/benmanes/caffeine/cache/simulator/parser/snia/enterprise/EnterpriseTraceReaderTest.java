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
package com.github.benmanes.caffeine.cache.simulator.parser.snia.enterprise;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the reader maps a byte-range read onto every 4 KiB block it touches. The Exchange ETW
 * reads are 512-aligned but rarely 4 KiB-aligned (in the sample 98% start 3584 bytes into a page),
 * so a request whose size is a multiple of the block still spans a trailing block that a bare
 * {@code ceil(size / block)} count drops.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class EnterpriseTraceReaderTest {

  @Test
  void spansTrailingBlockOnUnalignedOffset(@TempDir Path dir) throws IOException {
    // A verbatim DiskRead: offset 0x4e5ec22e00 is 512-aligned but 3584 bytes into its 4 KiB block,
    // so its 8 KiB read on disk 4 touches three blocks (the third is lost to a ceil(size) count)
    var file = Files.writeString(dir.resolve("exchange.csv"), "               DiskRead,     723291,"
        + " p0 (11008),      12260, 0xfffffade691b3010, 0x4e5ec22e00, 0x00002000,        4388,"
        + "        4, 0x000901,        4388,       0,  Unknown, 0xfffffade6bd7d7a0, \"Disk6\"\n");
    long[] keys = new EnterpriseTraceReader(file.toString()).keys().toArray();

    long base = (4L << 40) | (0x4e5ec22e00L / 4096);
    assertThat(keys).asList().containsExactly(base, base + 1, base + 2).inOrder();
  }

  @Test
  void alignedReadSpansExactBlocks(@TempDir Path dir) throws IOException {
    // The same read forced onto a 4 KiB boundary spans exactly its two blocks
    var file = Files.writeString(dir.resolve("exchange.csv"),
        "DiskRead, 0, p (0), 0, 0x0, 0x4e5ec22000, 0x00002000, 0, 4, 0x0, 0, 0, x, 0x0, \"d\"\n");
    long[] keys = new EnterpriseTraceReader(file.toString()).keys().toArray();

    long base = (4L << 40) | (0x4e5ec22000L / 4096);
    assertThat(keys).asList().containsExactly(base, base + 1).inOrder();
  }
}
