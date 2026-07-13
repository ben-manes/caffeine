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
package com.github.benmanes.caffeine.cache.simulator.parser.snia.cambridge;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class CambridgeTraceReaderTest {

  @Test
  void namespacesByDiskNumber(@TempDir Path dir) throws IOException {
    // The same block on two different disks must be distinct keys; concatenated multi-volume MSR
    // files would otherwise alias them (Timestamp,Host,Disk,Type,Offset,Size,...).
    var file = Files.writeString(dir.resolve("msr.csv"),
        "0,host,0,Read,0,512,0\n0,host,1,Read,0,512,0\n");
    long[] keys = new CambridgeTraceReader(file.toString()).keys().toArray();

    assertThat(keys).hasLength(2);
    assertThat(keys[0]).isNotEqualTo(keys[1]);
  }
}
