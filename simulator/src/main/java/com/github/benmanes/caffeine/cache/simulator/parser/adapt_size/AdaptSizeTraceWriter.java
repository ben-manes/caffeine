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
package com.github.benmanes.caffeine.cache.simulator.parser.adapt_size;

import java.io.IOException;
import java.io.OutputStream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceWriter;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;

/**
 * A writer for the trace format used by the authors of the AdaptSize simulator.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AdaptSizeTraceWriter extends TextTraceWriter {

  public AdaptSizeTraceWriter(OutputStream output) {
    super(output);
  }

  @Override
  public void writeEvent(int tick, AccessEvent event) throws IOException {
    writer().write(Long.toString(tick));
    writer().write(" ");
    writer().write(Long.toString(event.key()));
    writer().write(" ");
    writer().write(Integer.toString(event.weight()));
    writer().newLine();
  }
}
