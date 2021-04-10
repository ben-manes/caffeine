/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.stream.Stream;

import com.google.common.io.Closeables;

/**
 * A skeletal implementation that reads the trace file line by line as textual data.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class TextTraceReader extends AbstractTraceReader implements TraceReader {

  protected TextTraceReader(String filePath) {
    super(filePath);
  }

  /** Returns a stream of each line in the trace file. */
  @SuppressWarnings("PMD.CloseResource")
  protected Stream<String> lines() {
    InputStream input = readFile();
    Reader reader = new InputStreamReader(input, UTF_8);
    return new BufferedReader(reader).lines().map(String::trim)
        .onClose(() -> Closeables.closeQuietly(input));
  }
}
