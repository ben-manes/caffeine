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

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;

import com.github.benmanes.caffeine.base.UnsafeAccess;
import com.github.benmanes.caffeine.locks.NonReentrantLock;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
public class SynchronizedBenchmark {
  Lock nrlock = new NonReentrantLock();
  Lock rlock = new ReentrantLock();
  Object olock = new Object();
  int counter;

  @Benchmark  @Group("mixed") @GroupThreads(1)
  public void mixed_monitor() {
    UnsafeAccess.UNSAFE.monitorEnter(olock);
    try {
      counter++;
    } finally {
      UnsafeAccess.UNSAFE.monitorExit(olock);
    }
  }

  @Benchmark  @Group("mixed") @GroupThreads(3)
  public void mixed_sync() {
    synchronized (olock) {
      counter++;
    }
  }

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
}
