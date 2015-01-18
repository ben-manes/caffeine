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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.simulator.Synthetic;
import com.github.benmanes.caffeine.cache.tracing.CacheEvent;
import com.google.common.jimfs.Jimfs;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LogReaderTest {
  static final int FILE_SIZE = 1000;

  @Test(dataProvider = "events")
  public void readTextLog(BufferedReader reader, List<CacheEvent> written) {
    List<CacheEvent> read = LogReader.textLogStream(reader).collect(Collectors.toList());
    assertThat(read, is(equalTo(written)));
  }

  @DataProvider(name = "events")
  Object[][] generateFile() throws IOException {
    Path path = Jimfs.newFileSystem().getPath("caffeine.log");
    List<CacheEvent> events = Synthetic.scrambledZipfian(FILE_SIZE).collect(Collectors.toList());
    Iterable<String> lines = () -> events.stream().map(event -> event.toString()).iterator();
    Files.write(path, lines);
    return new Object[][] {{ Files.newBufferedReader(path), events }};
  }
}
