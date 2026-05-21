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
package com.github.benmanes.caffeine.cache.simulator.parser.libcachesim.oracle;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.parser.BinaryTraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;

/**
 * A reader for the OracleGeneral binary trace format used by
 * <a href="https://github.com/1a1a11a/libCacheSim">libCacheSim</a> and distributed by the
 * <a href="https://github.com/cacheMon/cache_dataset">cacheMon cache_dataset</a> project.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class OracleGeneralTraceReader extends BinaryTraceReader {

  public OracleGeneralTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Set<Characteristic> characteristics() {
    return Set.of(WEIGHTED);
  }

  @Override
  protected AccessEvent readEvent(DataInputStream input) throws IOException {
    // uint32_t real_time: wall-clock timestamp
    // uint64_t obj_id: object identifier
    // uint32_t obj_size: object byte size
    // int64_t next_access_vtime: the next access virtual time

    input.skipNBytes(4);
    long objId = Long.reverseBytes(input.readLong());
    int objSize = Integer.reverseBytes(input.readInt());
    input.skipNBytes(8);
    return AccessEvent.forKeyAndWeight(objId, Math.max(objSize, 1));
  }
}
