/*
 * Copyright 2021 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.cloud_physics;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.parser.BinaryTraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;

/**
 * A reader for the trace files provided by the author of LIRS2. See
 * <a href="https://github.com/zhongch4g/LIRS2">traces</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CloudPhysicsTraceReader extends BinaryTraceReader {

  public CloudPhysicsTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Set<Characteristic> characteristics() {
    return Set.of();
  }

  @Override
  protected AccessEvent readEvent(DataInputStream input) throws IOException {
    return AccessEvent.forKey(Integer.toUnsignedLong(input.readInt()));
  }
}
