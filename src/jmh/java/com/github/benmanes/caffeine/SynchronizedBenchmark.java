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
package com.github.benmanes.caffeine;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
public class SynchronizedBenchmark {
  Lock nrlock = new NonReentrantLock();
  Lock rlock = new ReentrantLock();
  Object olock = new Object();
  int counter;

  @Benchmark @Threads(1)
  public void synchronized_noContention() {
    synchronized (olock) {
      counter++;
    }
  }

  @Benchmark @Threads(4)
  public void synchronized_contention() {
    synchronized (olock) {
      counter++;
    }
  }

  @Benchmark @Threads(1)
  public void monitor_noContention() {
    UnsafeAccess.UNSAFE.monitorEnter(olock);
    try {
      counter++;
    } finally {
      UnsafeAccess.UNSAFE.monitorExit(olock);
    }
  }

  @Benchmark @Threads(4)
  public void monitor_contention() {
    UnsafeAccess.UNSAFE.monitorEnter(olock);
    try {
      counter++;
    } finally {
      UnsafeAccess.UNSAFE.monitorExit(olock);
    }
  }

  @Benchmark @Threads(1)
  public void reentrantLock_noContention() {
    rlock.lock();
    try {
      counter++;
    } finally {
      rlock.unlock();
    }
  }

  @Benchmark @Threads(4)
  public void reentrantLock_contention() {
    rlock.lock();
    try {
      counter++;
    } finally {
      rlock.unlock();
    }
  }

  @Benchmark @Threads(1)
  public void nonReentrantLock_noContention() {
    nrlock.lock();
    try {
      counter++;
    } finally {
      nrlock.unlock();
    }
  }

  @Benchmark @Threads(4)
  public void nonReentrantLock_contention() {
    nrlock.lock();
    try {
      counter++;
    } finally {
      nrlock.unlock();
    }
  }

  static final class NonReentrantLock extends AbstractQueuedSynchronizer implements Lock {
    static final long serialVersionUID = 1L;

    Thread owner;

    @Override
    public void lock() {
      acquire(1);
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
      acquireInterruptibly(1);
    }

    @Override
    public boolean tryLock() {
      return tryAcquire(1);
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
      return tryAcquireNanos(1, unit.toNanos(time));
    }

    @Override
    public void unlock() {
      release(1);
    }

    @Override
    public Condition newCondition() {
      return new ConditionObject();
    }

    @Override
    protected boolean tryAcquire(int acquires) {
      if (compareAndSetState(0, 1)) {
        owner = Thread.currentThread();
        return true;
      }
      return false;
    }

    @Override
    protected boolean tryRelease(int releases) {
      if (Thread.currentThread() != owner) {
        throw new IllegalMonitorStateException();
      }
      owner = null;
      setState(0);
      return true;
    }

    @Override
    protected boolean isHeldExclusively() {
      return (getState() != 0) && (owner == Thread.currentThread());
    }
  }
}
