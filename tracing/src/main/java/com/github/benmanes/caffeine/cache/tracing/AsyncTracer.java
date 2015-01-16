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
package com.github.benmanes.caffeine.cache.tracing;

import java.util.concurrent.Executor;

import com.github.benmanes.caffeine.cache.tracing.CacheEvent.Action;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslatorTwoArg;
import com.lmax.disruptor.dsl.Disruptor;

/**
 * A tracing implementation implemented as a Disruptor multi-producer.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AsyncTracer implements Tracer {
  final EventTranslatorTwoArg<CacheEvent, Action, Object> translator;
  final Disruptor<CacheEvent> disruptor;

  @SuppressWarnings("unchecked")
  public AsyncTracer(EventHandler<CacheEvent> handler, int ringBufferSize, Executor executor) {
    disruptor = new Disruptor<>(CacheEvent::new, ringBufferSize, executor);
    translator = (event, seq, action, object) -> {
      event.hashCode = object.hashCode();
      event.action = action;
    };
    disruptor.handleEventsWith(handler);
    disruptor.start();
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
