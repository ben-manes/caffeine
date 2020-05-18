/*
 * Copyright 2020 Ben Manes. All Rights Reserved.
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

import java.io.Closeable;
import java.io.IOException;

import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;

/**
 * A writer to output to an access trace format.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public interface TraceWriter extends Closeable {

  /** Writes the header for the trace format. */
  default void writeHeader() throws IOException {};

  /** Writes the event in the trace format. */
  void writeEvent(int tick, AccessEvent event) throws IOException;

  /** Writes the footer for the trace format. */
  default void writeFooter() throws IOException {};
}
