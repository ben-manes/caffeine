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
package com.github.benmanes.caffeine.cache.tracing.async;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.concurrent.ThreadSafe;

import com.github.benmanes.caffeine.cache.tracing.TraceEvent;
import com.github.benmanes.caffeine.cache.tracing.TraceEventFormats;

/**
 * A handler that records events to a log file in the binary format.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@ThreadSafe
public final class BinaryLogEventHandler implements LogEventHandler {
  final DataOutputStream output;

  public BinaryLogEventHandler(Path filePath) {
    try {
      output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(filePath)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void onEvent(TraceEvent event, long sequence, boolean endOfBatch) throws IOException {
    TraceEventFormats.writeBinaryRecord(event, output);
    if (endOfBatch) {
      output.flush();
    }
  }

  @Override
  public void close() throws IOException {
    output.close();
  }
}
