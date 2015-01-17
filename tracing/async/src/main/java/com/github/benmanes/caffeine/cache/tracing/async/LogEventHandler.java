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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.concurrent.ThreadSafe;

import com.lmax.disruptor.EventHandler;

/**
 * A cache event consumer that records to a log file.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@ThreadSafe
final class LogEventHandler implements EventHandler<CacheEvent> {
  final BufferedWriter writer;
  final LogFormat format;

  LogEventHandler(Path filePath, LogFormat format) {
    try {
      this.writer = Files.newBufferedWriter(filePath);
      this.format = format;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void onEvent(CacheEvent event, long sequence, boolean endOfBatch) throws IOException {
    format.appendTo(writer, event.cacheId, event.action.name(), event.hash, event.timestamp);
    if (endOfBatch) {
      writer.flush();
    }
  }
}
