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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.mockito.MockitoAnnotations;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.tracing.CacheEvent.Action;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jayway.awaitility.Awaitility;
import com.jayway.awaitility.core.ConditionFactory;
import com.lmax.disruptor.EventHandler;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Test(singleThreaded = true)
public final class AsyncTracerTest {
  InMemoryEventHandler consumer;
  Tracer tracer;

  @BeforeClass
  public void beforeClqss() {
    consumer = new InMemoryEventHandler();
    tracer = new AsyncTracer(consumer, 64, Executors.newSingleThreadExecutor(
        new ThreadFactoryBuilder().setDaemon(true).build()));
  }

  @BeforeMethod
  public void beforeMethod() {
    MockitoAnnotations.initMocks(this);
  }

  @Test
  public void example() throws Exception {
    for (int i = 0; i < 100; i++) {
      tracer.recordCreate(new Object());
    }
    await().until(() -> consumer.events.size() == 100);

    Set<Integer> unique = new HashSet<>();
    for (CacheEvent event : consumer.events) {
      unique.add(event.hashCode);
      assertThat(event.action, is(Action.CREATE));
    }
    assertThat(unique.size(), is(greaterThan(1)));
  }

  ConditionFactory await() {
    return Awaitility.with()
        .pollDelay(1, TimeUnit.MILLISECONDS).and()
        .pollInterval(1, TimeUnit.MILLISECONDS)
        .await();
  }

  static final class InMemoryEventHandler implements EventHandler<CacheEvent> {
    Queue<CacheEvent> events = new ConcurrentLinkedQueue<>();

    @Override
    public void onEvent(CacheEvent event, long sequence, boolean endOfBatch) throws Exception {
      CacheEvent copy = new CacheEvent();
      copy.hashCode = event.hashCode;
      copy.action = event.action;
      events.add(copy);
    }
  }
}
