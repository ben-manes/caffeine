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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.ConcurrentTestHarness;
import com.github.benmanes.caffeine.cache.tracing.Tracer;
import com.google.common.jimfs.Jimfs;
import com.lmax.disruptor.util.DaemonThreadFactory;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AsyncTracerTest {
  Executor executor;
  Path filePath;

  @BeforeClass
  public void beforeClqss() throws IOException {
    executor = Executors.newSingleThreadExecutor(DaemonThreadFactory.INSTANCE);
    filePath = Jimfs.newFileSystem().getPath("caffeine.log");
    System.setProperty(AsyncTracer.TRACING_FILE_PROPERTY,
        Files.createTempFile("caffiene", ".log").toString());
  }

  @Test
  public void serviceLoader() {
    Tracer defaultTracer = Tracer.getDefault();
    assertThat(defaultTracer, is(not(Tracer.disabled())));
  }

  @Test(dataProvider = "tracer")
  public void publishEvents(AsyncTracer tracer) throws Exception {
    ConcurrentTestHarness.timeTasks(10, () -> {
      for (int i = 0; i < 100; i++) {
        tracer.recordCreate(new Object());
      }
    });
    tracer.shutdown();
    assertThat(Files.lines(filePath).count(), is(1000L));
  }

  @DataProvider(name = "tracer")
  public Object[][] providerTracer() {
    AsyncTracer tracer = new AsyncTracer(new TextLogEventHandler(filePath), 64, executor);
    return new Object[][] {{ tracer }};
  }
}
