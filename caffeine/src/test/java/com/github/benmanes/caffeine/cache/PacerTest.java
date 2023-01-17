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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
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

  Pacer pacer;

  @BeforeMethod
  public void beforeMethod() throws Exception {
    MockitoAnnotations.openMocks(this).close();
    pacer = new Pacer(scheduler);
  }

  @Test
  public void schedule_initialize() {
    long delay = random.nextInt(Ints.saturatedCast(Pacer.TOLERANCE));
    doReturn(future)
        .when(scheduler).schedule(executor, command, Pacer.TOLERANCE, TimeUnit.NANOSECONDS);
    pacer.schedule(executor, command, NOW, delay);

    assertThat(pacer.isScheduled()).isTrue();
    assertThat(pacer.future).isSameInstanceAs(future);
    assertThat(pacer.nextFireTime).isEqualTo(NOW + Pacer.TOLERANCE);
  }

  @Test
  public void schedule_initialize_recurse() {
    long delay = random.nextInt(Ints.saturatedCast(Pacer.TOLERANCE));
    doAnswer(invocation -> {
      assertThat(pacer.future).isNull();
      assertThat(pacer.nextFireTime).isNotEqualTo(0);
      pacer.schedule(executor, command, NOW, delay);
      return future;
    }).when(scheduler).schedule(executor, command, Pacer.TOLERANCE, TimeUnit.NANOSECONDS);

    pacer.schedule(executor, command, NOW, delay);

    assertThat(pacer.isScheduled()).isTrue();
    assertThat(pacer.future).isSameInstanceAs(future);
    assertThat(pacer.nextFireTime).isEqualTo(NOW + Pacer.TOLERANCE);
  }

  @Test
  public void schedule_cancel_schedule() {
    long fireTime = NOW + Pacer.TOLERANCE;
    long delay = random.nextInt(Ints.saturatedCast(Pacer.TOLERANCE));
    doReturn(future)
        .when(scheduler).schedule(executor, command, Pacer.TOLERANCE, TimeUnit.NANOSECONDS);

    pacer.schedule(executor, command, NOW, delay);
    assertThat(pacer.nextFireTime).isEqualTo(fireTime);
    assertThat(pacer.future).isSameInstanceAs(future);
    assertThat(pacer.isScheduled()).isTrue();

    pacer.cancel();
    verify(future).cancel(false);
    assertThat(pacer.nextFireTime).isEqualTo(0);
    assertThat(pacer.isScheduled()).isFalse();
    assertThat(pacer.future).isNull();

    pacer.schedule(executor, command, NOW, delay);
    assertThat(pacer.isScheduled()).isTrue();
    assertThat(pacer.nextFireTime).isEqualTo(fireTime);
    assertThat(pacer.future).isSameInstanceAs(future);
  }

  @Test
  public void scheduled_afterNextFireTime_skip() {
    pacer.nextFireTime = NOW + ONE_MINUTE_IN_NANOS;
    pacer.future = future;

    long expectedNextFireTime = pacer.nextFireTime;
    pacer.schedule(executor, command, NOW, ONE_MINUTE_IN_NANOS);
    verifyNoInteractions(scheduler, executor, command, future);

    assertThat(pacer.isScheduled()).isTrue();
    assertThat(pacer.future).isSameInstanceAs(future);
    assertThat(pacer.nextFireTime).isEqualTo(expectedNextFireTime);
  }

  @Test
  public void schedule_beforeNextFireTime_skip() {
    pacer.nextFireTime = NOW + ONE_MINUTE_IN_NANOS;
    pacer.future = future;

    long expectedNextFireTime = pacer.nextFireTime;
    long delay = ONE_MINUTE_IN_NANOS
        - Math.max(1, random.nextInt(Ints.saturatedCast(Pacer.TOLERANCE)));
    pacer.schedule(executor, command, NOW, delay);
    verifyNoInteractions(scheduler, executor, command, future);

    assertThat(pacer.isScheduled()).isTrue();
    assertThat(pacer.future).isSameInstanceAs(future);
    assertThat(pacer.nextFireTime).isEqualTo(expectedNextFireTime);
  }

  @Test
  public void schedule_beforeNextFireTime_minimumDelay() {
    pacer.nextFireTime = NOW + ONE_MINUTE_IN_NANOS;
    pacer.future = future;

    long delay = random.nextInt(Ints.saturatedCast(Pacer.TOLERANCE));
    doReturn(future)
        .when(scheduler).schedule(executor, command, Pacer.TOLERANCE, TimeUnit.NANOSECONDS);
    pacer.schedule(executor, command, NOW, delay);

    verify(future).cancel(false);
    verify(scheduler).schedule(executor, command, Pacer.TOLERANCE, TimeUnit.NANOSECONDS);

    verifyNoInteractions(executor, command);
    verifyNoMoreInteractions(scheduler, future);

    assertThat(pacer.future).isSameInstanceAs(future);
    assertThat(pacer.nextFireTime).isEqualTo(NOW + Pacer.TOLERANCE);
    assertThat(pacer.isScheduled()).isTrue();
  }

  @Test
  public void schedule_beforeNextFireTime_customDelay() {
    pacer.nextFireTime = NOW + ONE_MINUTE_IN_NANOS;
    pacer.future = future;

    long delay = (Pacer.TOLERANCE + Math.max(1, random.nextInt()));
    doReturn(future)
        .when(scheduler).schedule(executor, command, delay, TimeUnit.NANOSECONDS);
    pacer.schedule(executor, command, NOW, delay);

    verify(future).cancel(false);
    verify(scheduler).schedule(executor, command, delay, TimeUnit.NANOSECONDS);

    verifyNoInteractions(executor, command);
    verifyNoMoreInteractions(scheduler, future);

    assertThat(pacer.future).isSameInstanceAs(future);
    assertThat(pacer.nextFireTime).isEqualTo(NOW + delay);
    assertThat(pacer.isScheduled()).isTrue();
  }

  @Test
  public void cancel_initialize() {
    pacer.cancel();
    assertThat(pacer.nextFireTime).isEqualTo(0);
    assertThat(pacer.isScheduled()).isFalse();
    assertThat(pacer.future).isNull();
  }

  @Test
  public void cancel_scheduled() {
    pacer.nextFireTime = NOW + ONE_MINUTE_IN_NANOS;
    pacer.future = future;

    pacer.cancel();
    verify(future).cancel(false);
    assertThat(pacer.future).isNull();
    assertThat(pacer.isScheduled()).isFalse();
    assertThat(pacer.nextFireTime).isEqualTo(0);
  }

  @Test
  public void isScheduled_nullFuture() {
    pacer.future = null;
    assertThat(pacer.isScheduled()).isFalse();
  }

  @Test
  public void isScheduled_doneFuture() {
    pacer.future = DisabledFuture.INSTANCE;
    assertThat(pacer.isScheduled()).isFalse();
  }

  @Test
  public void isScheduled_inFlight() {
    pacer.future = new CompletableFuture<>();
    assertThat(pacer.isScheduled()).isTrue();
  }
}
