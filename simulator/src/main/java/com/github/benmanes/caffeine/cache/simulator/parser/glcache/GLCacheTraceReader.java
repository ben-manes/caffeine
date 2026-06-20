/*
 * Copyright 2023 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.glcache;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.parser.BinaryTraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.google.common.primitives.Ints;

/**
 * A reader for the trace files provided by the author of GL-Cache. See
 * <a href="https://github.com/Thesys-lab/fast23-GLCache#traces">traces</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("IdentifierName")
public final class GLCacheTraceReader extends BinaryTraceReader {

  public GLCacheTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Set<Characteristic> characteristics() {
    return Set.of(WEIGHTED);
  }

  @Override
  protected AccessEvent readEvent(DataInputStream input) throws IOException {
    /*
     * struct {
     *   uint32_t timestamp;
     *   uint64_t obj_id;
     *   uint32_t obj_size;
     *   int64_t next_access_vtime;  // -1 if no next access
     * }
     */
    input.skipNBytes(4);
    long key = Long.reverseBytes(input.readLong());
    long weight = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
    input.skipNBytes(8);

    return AccessEvent.forKeyAndWeight(key, Math.max(Ints.saturatedCast(weight), 1));
  }
}
