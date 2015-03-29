/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.locks;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;

import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.Awaits;
import com.github.benmanes.caffeine.ConcurrentTestHarness;
import com.google.common.testing.SerializableTester;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class NonReentrantLockTest {

  @DataProvider(name = "lock")
  public Object[][] providesLock() {
    return new Object[][] {{ new NonReentrantLock() }};
  }

  @Test(dataProvider = "lock")
  public void tryLock(NonReentrantLock lock) {
    assertThat(lock.tryLock(), is(true));
    lock.unlock();
  }

  @Test(dataProvider = "lock")
  public void tryLock_timed(NonReentrantLock lock) throws InterruptedException {
    assertThat(lock.tryLock(1, TimeUnit.MINUTES), is(true));
    lock.unlock();
  }

  @Test(dataProvider = "lock")
  public void lockInterruptibly(NonReentrantLock lock) throws InterruptedException {
    lock.lockInterruptibly();
    lock.unlock();
  }

  @Test(dataProvider = "lock")
  public void lock(NonReentrantLock lock) {
    lock.lock();
    assertThat(lock.tryLock(), is(false));
    assertThat(lock.isHeldByCurrentThread(), is(true));
    assertThat(lock.getOwner(), is(Thread.currentThread()));
    lock.unlock();
  }

  @Test(dataProvider = "lock")
  public void lock_exclusive(NonReentrantLock lock) {
    Thread testThread = Thread.currentThread();
    ConcurrentTestHarness.execute(() -> {
      lock.lock();
      Awaits.await().until(lock::hasQueuedThreads);
      assertThat(lock.getQueueLength(), is(1));
      assertThat(lock.getQueuedThreads(), contains(testThread));
      assertThat(lock.hasQueuedThread(testThread), is(true));
      lock.unlock();
    });
    Awaits.await().until(lock::isLocked);
    assertThat(lock.tryLock(), is(false));
    lock.lock();
    lock.unlock();
  }

  @Test(dataProvider = "lock")
  public void lock_error(NonReentrantLock lock) {
    Condition condition = Mockito.mock(Condition.class);

    try {
      lock.hasWaiters(condition);
      Assert.fail();
    } catch (IllegalArgumentException e) {}

    try {
      lock.getWaitQueueLength(condition);
      Assert.fail();
    } catch (IllegalArgumentException e) {}

    try {
      lock.getWaitingThreads(condition);
      Assert.fail();
    } catch (IllegalArgumentException e) {}

    try {
      lock.sync.tryRelease(1);
      Assert.fail();
    } catch (IllegalMonitorStateException e) {}
  }

  @Test(dataProvider = "lock")
  public void condition(NonReentrantLock lock) {
    Condition condition = lock.newCondition();
    AtomicBoolean ready = new AtomicBoolean();
    Thread thread = new Thread(() -> {
      lock.lock();
      ready.set(true);
      condition.awaitUninterruptibly();
    });
    thread.start();
    Awaits.await().untilTrue(ready);
    lock.lock();

    assertThat(lock.hasWaiters(condition), is(true));
    assertThat(lock.getWaitQueueLength(condition), is(1));
    assertThat(lock.getWaitingThreads(condition), contains(thread));

    condition.signal();
    lock.unlock();
  }

  @Test(dataProvider = "lock")
  public void serialize(NonReentrantLock lock) {
    lock.lock();
    NonReentrantLock copy = SerializableTester.reserialize(lock);
    assertThat(copy.isLocked(), is(false));
  }
}
