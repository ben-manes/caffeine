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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.annotation.concurrent.ThreadSafe;

import com.github.benmanes.caffeine.cache.tracing.CacheEvent;
import com.github.benmanes.caffeine.cache.tracing.CacheEvent.Action;
import com.github.benmanes.caffeine.cache.tracing.Tracer;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslatorTwoArg;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.util.DaemonThreadFactory;

/**
 * A tracing implementation implemented as a Disruptor multi-producer.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@ThreadSafe
public final class AsyncTracer implements Tracer {
  public static final String TRACING_FILE_PROPERTY = "cache.tracing.file";

  final EventTranslatorTwoArg<CacheEvent, Action, Object> translator;
  final Disruptor<CacheEvent> disruptor;

  public AsyncTracer() {
    this(new TextLogEventHandler(filePath()), 64,
        Executors.newSingleThreadExecutor(DaemonThreadFactory.INSTANCE));
  }

  private static Path filePath() {
    String property = System.getProperty(TRACING_FILE_PROPERTY, "caffeine.log");
    return Paths.get(property);
  }

  @SuppressWarnings("unchecked")
  public AsyncTracer(EventHandler<CacheEvent> handler, int ringBufferSize, Executor executor) {
    translator = (event, seq, action, object) -> {
      event.setTimestamp(System.nanoTime());
      event.setHash(object.hashCode());
      event.setAction(action);
    };
    disruptor = new Disruptor<>(CacheEvent::new, ringBufferSize, executor);
    disruptor.handleEventsWith(handler);
    disruptor.start();
  }

  public void shutdown() {
    disruptor.shutdown();
  }

  @Override
  public void recordCreate(Object o) {
    publish(Action.CREATE, o);
  }

  @Override
  public void recordRead(Object o) {
    publish(Action.READ, o);
  }

  @Override
  public void recordUpdate(Object o) {
    publish(Action.UPDATE, o);
  }

  @Override
  public void recordDelete(Object o) {
    publish(Action.DELETE, o);
  }

  void publish(Action action, Object o) {
    disruptor.getRingBuffer().publishEvent(translator, action, o);
  }
}
