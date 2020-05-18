/*
 * Copyright 2019 Ben Manes. All Rights Reserved.
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

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.simulator.parser.adapt_size.AdaptSizeTraceWriter;
import com.github.benmanes.caffeine.cache.simulator.parser.climb.ClimbTraceWriter;

/**
 * The trace output format.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("ImmutableEnumChecker")
public enum OutputFormat {
  ADAPT_SIZE(AdaptSizeTraceWriter::new),
  CLIMB(ClimbTraceWriter::new);

  private final Function<OutputStream, TraceWriter> factory;

  OutputFormat(Function<OutputStream, TraceWriter> factory) {
    this.factory = factory;
  }

  public TraceWriter writer(OutputStream output) {
    return factory.apply(new BufferedOutputStream(output));
  }
}
