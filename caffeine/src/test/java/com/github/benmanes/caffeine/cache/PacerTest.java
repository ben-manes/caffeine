/*
 * Copyright 2019 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.primitives.Ints;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Test(singleThreaded = true)
public final class PacerTest {
  private static final long ONE_MINUTE_IN_NANOS = TimeUnit.MINUTES.toNanos(1);
  private static final Random random = new Random();
  private static final long NOW = random.nextLong();

  @Mock Scheduler scheduler;
  @Mock Executor executor;
  @Mock Runnable command;
  @Mock Future<?> future;
  AutoCloseable mocks;

  Pacer pacer;

  @BeforeMethod
  public void beforeMethod() {
    mocks = MockitoAnnotations.openMocks(this);
    pacer = new Pacer(scheduler);
  }

  @AfterMethod
  public void afterMethod() throws Exception {
    mocks.close();
  }

  @Test
  public void scheduledAfterNextFireTime_skip() {
    pacer.nextFireTime = NOW + ONE_MINUTE_IN_NANOS;
    pacer.future = future;

    long expectedNextFireTime = pacer.nextFireTime;
    pacer.schedule(executor, command, NOW, ONE_MINUTE_IN_NANOS);

    assertThat(pacer.future, is(future));
    assertThat(pacer.nextFireTime, is(expectedNextFireTime));
    verifyNoInteractions(scheduler, executor, command, future);
  }

  @Test
  public void scheduledBeforeNextFireTime_skip() {
    pacer.nextFireTime = NOW + ONE_MINUTE_IN_NANOS;
    pacer.future = future;

    long expectedNextFireTime = pacer.nextFireTime;
    long delay = ONE_MINUTE_IN_NANOS
        - Math.max(1, random.nextInt(Ints.saturatedCast(Pacer.TOLERANCE)));
    pacer.schedule(executor, command, NOW, delay);

    assertThat(pacer.future, is(future));
    assertThat(pacer.nextFireTime, is(expectedNextFireTime));
    verifyNoInteractions(scheduler, executor, command, future);
  }

  @Test
  public void scheduledBeforeNextFireTime_minimumDelay() {
    pacer.nextFireTime = NOW + ONE_MINUTE_IN_NANOS;
    pacer.future = future;

    long delay = random.nextInt(Ints.saturatedCast(Pacer.TOLERANCE));
    doReturn(DisabledFuture.INSTANCE)
        .when(scheduler).schedule(executor, command, Pacer.TOLERANCE, TimeUnit.NANOSECONDS);
    pacer.schedule(executor, command, NOW, delay);

    assertThat(pacer.future, is(DisabledFuture.INSTANCE));
    assertThat(pacer.nextFireTime, is(NOW + Pacer.TOLERANCE));

    verify(future).cancel(anyBoolean());
    verify(scheduler).schedule(executor, command, Pacer.TOLERANCE, TimeUnit.NANOSECONDS);

    verifyNoInteractions(executor, command);
    verifyNoMoreInteractions(scheduler, future);
  }

  @Test
  public void scheduledBeforeNextFireTime_customDelay() {
    pacer.nextFireTime = NOW + ONE_MINUTE_IN_NANOS;
    pacer.future = future;

    long delay = (Pacer.TOLERANCE + Math.max(1, random.nextInt()));
    doReturn(DisabledFuture.INSTANCE)
        .when(scheduler).schedule(executor, command, delay, TimeUnit.NANOSECONDS);
    pacer.schedule(executor, command, NOW, delay);

    assertThat(pacer.future, is(DisabledFuture.INSTANCE));
    assertThat(pacer.nextFireTime, is(NOW + delay));

    verify(future).cancel(anyBoolean());
    verify(scheduler).schedule(executor, command, delay, TimeUnit.NANOSECONDS);

    verifyNoInteractions(executor, command);
    verifyNoMoreInteractions(scheduler, future);
  }
}
