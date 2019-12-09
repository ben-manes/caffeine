/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.scarab;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.parser.BinaryTraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.google.common.collect.ImmutableSet;

/**
 * A reader for the trace files provided by
 * <a href="http://www.scarabresearch.com/">Scarab Research</a>.
 *
 * @author phraktle@gmail.com (Viktor Szathmáry)
 */
public final class ScarabTraceReader extends BinaryTraceReader {

  public ScarabTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Set<Characteristic> characteristics() {
    return ImmutableSet.of();
  }

  @Override
  protected AccessEvent readEvent(DataInputStream input) throws IOException {
    return AccessEvent.forKey(input.readLong());
  }
}
