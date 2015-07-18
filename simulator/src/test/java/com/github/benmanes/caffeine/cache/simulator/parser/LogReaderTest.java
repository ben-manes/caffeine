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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.simulator.Synthetic;
import com.github.benmanes.caffeine.cache.tracing.TraceEvent;
import com.github.benmanes.caffeine.cache.tracing.TraceEvent.Action;
import com.github.benmanes.caffeine.cache.tracing.async.BinaryLogEventHandler;
import com.github.benmanes.caffeine.cache.tracing.async.LogEventHandler;
import com.github.benmanes.caffeine.cache.tracing.async.TextLogEventHandler;
import com.google.common.jimfs.Jimfs;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LogReaderTest {
  static final int FILE_SIZE = 1_000;

  @Test
  public void readTextLog() throws Exception {
    List<TraceEvent> events = makeEvents();
    Path filePath = eventsAsLogFile(events, TextLogEventHandler::new);
    List<TraceEvent> read = LogReader.textLogStream(filePath).collect(Collectors.toList());
    assertThat(read, is(equalTo(events)));
  }

  @Test
  public void readBinaryLog() throws Exception {
    List<TraceEvent> events = makeEvents();
    Path filePath = eventsAsLogFile(events, BinaryLogEventHandler::new);
    List<TraceEvent> read = LogReader.binaryLogStream(filePath).collect(Collectors.toList());
    assertThat(read, is(equalTo(events)));
  }

  private List<TraceEvent> makeEvents() {
    return Synthetic.counter(0, FILE_SIZE).map(count -> {
        return new TraceEvent(null, 0, Action.WRITE, count.hashCode(), 1, 0L);
    }).collect(Collectors.toList());
  }

  private Path eventsAsLogFile(List<TraceEvent> events, Function<Path, LogEventHandler> handlerFun)
      throws Exception {
    Path path = Jimfs.newFileSystem().getPath("caffeine.log");
    LogEventHandler handler = handlerFun.apply(path);
    for (TraceEvent event : events) {
      handler.onEvent(event, 1, true);
    }
    handler.close();
    return path;
  }
}
