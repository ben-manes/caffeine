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

import static com.jayway.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.tracing.Tracer;
import com.google.common.jimfs.Jimfs;
import com.lmax.disruptor.util.DaemonThreadFactory;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AsyncTracerTest {
  Path badPath = Jimfs.newFileSystem().getPath("\\a/b");
  ExecutorService executor;
  Path filePath;

  @BeforeClass
  public void beforeClqss() throws IOException {
    executor = Executors.newSingleThreadExecutor(DaemonThreadFactory.INSTANCE);
    filePath = Jimfs.newFileSystem().getPath("caffeine.log");
    System.setProperty(AsyncTracer.TRACING_FILE,
        Files.createTempFile("caffiene", ".log").toString());
  }

  @Test
  public void serviceLoader() {
    Tracer defaultTracer = Tracer.getDefault();
    assertThat(defaultTracer, is(not(Tracer.disabled())));
  }

  @Test(dataProvider = "tracer")
  public void publishEvents(AsyncTracer tracer, boolean plainText) throws Exception {
    int nThreads = 10;
    Phaser phaser = new Phaser(nThreads);
    AtomicInteger done = new AtomicInteger();

    Runnable task = () -> {
      phaser.arriveAndAwaitAdvance();
      for (int i = 0; i < 100; i++) {
        tracer.register(Integer.toString(i));
        tracer.recordRead(i, i);
        tracer.recordWrite(i, i, i);
        tracer.recordDelete(i, i);
      }
      done.incrementAndGet();
    };
    for (int i = 0; i < nThreads; i++) {
      ForkJoinPool.commonPool().execute(task);
    }
    await().untilAtomic(done, is(nThreads));

    tracer.shutdown();
    if (plainText) {
      assertThat(Files.lines(filePath).count(), is(4000L));
    }
  }

  @Test(dataProvider = "formats")
  public void formats(String format, Class<?> eventHandlerClass) {
    runWithFormat(format, () ->
      assertThat(new AsyncTracer().handler, is(instanceOf(eventHandlerClass))));
  }

  @Test(expectedExceptions = UncheckedIOException.class)
  public void badFilePath_text() throws IOException {
    new TextLogEventHandler(badPath).close();
  }

  @Test(expectedExceptions = UncheckedIOException.class)
  public void badFilePath_binary() throws IOException {
    new BinaryLogEventHandler(badPath).close();
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void badFileFormat() {
    runWithFormat("braille", AsyncTracer::new);
  }

  private static void runWithFormat(String format, Runnable action) {
    System.setProperty(AsyncTracer.TRACING_FORMAT, format);
    try {
      action.run();
    } finally {
      System.getProperties().remove(AsyncTracer.TRACING_FORMAT);
    }
  }

  @DataProvider(name = "tracer")
  public Object[][] providesTracer() {
    return new Object[][] {
        { new AsyncTracer(new TextLogEventHandler(filePath), 64, executor), true },
        { new AsyncTracer(new BinaryLogEventHandler(filePath), 64, executor), false },
    };
  }

  @DataProvider(name = "formats")
  public Object[][] providesFormats() {
    return new Object[][] {
        { "text", TextLogEventHandler.class },
        { "binary", BinaryLogEventHandler.class },
    };
  }
}
