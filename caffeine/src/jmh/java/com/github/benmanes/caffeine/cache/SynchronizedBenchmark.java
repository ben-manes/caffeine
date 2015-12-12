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
package com.github.benmanes.caffeine.cache;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;

import com.github.benmanes.caffeine.base.UnsafeAccess;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
public class SynchronizedBenchmark {
  final Lock nrlock = new NonReentrantLock();
  final Lock rlock = new ReentrantLock();
  final Object olock = new Object();
  int counter;

  /* ---------------- synchronized -------------- */

  @Benchmark @Threads(1)
  public void synchronized_noContention() {
    synchronized (olock) {
      counter++;
    }
  }

  @Benchmark @Threads(4)
  public int synchronized_contention() {
    synchronized (olock) {
      return counter++;
    }
  }

  /* ---------------- monitor byte code -------------- */

  @Benchmark @Threads(1)
  public int monitor_noContention() {
    UnsafeAccess.UNSAFE.monitorEnter(olock);
    try {
      return counter++;
    } finally {
      UnsafeAccess.UNSAFE.monitorExit(olock);
    }
  }

  @Benchmark @Threads(4)
  public int monitor_contention() {
    UnsafeAccess.UNSAFE.monitorEnter(olock);
    try {
      return counter++;
    } finally {
      UnsafeAccess.UNSAFE.monitorExit(olock);
    }
  }

  /* ---------------- mixed -------------- */

  @Benchmark  @Group("mixed") @GroupThreads(1)
  public int mixed_monitor() {
    UnsafeAccess.UNSAFE.monitorEnter(olock);
    try {
      return counter++;
    } finally {
      UnsafeAccess.UNSAFE.monitorExit(olock);
    }
  }

  @Benchmark  @Group("mixed") @GroupThreads(3)
  public int mixed_sync() {
    synchronized (olock) {
      return counter++;
    }
  }

  /* ---------------- ReentrantLock -------------- */

  @Benchmark @Threads(1)
  public int reentrantLock_noContention() {
    rlock.lock();
    try {
      return counter++;
    } finally {
      rlock.unlock();
    }
  }

  @Benchmark @Threads(4)
  public int reentrantLock_contention() {
    rlock.lock();
    try {
      return counter++;
    } finally {
      rlock.unlock();
    }
  }

  /* ---------------- NonReentrantLock -------------- */

  @Benchmark @Threads(1)
  public int nonReentrantLock_noContention() {
    nrlock.lock();
    try {
      return counter++;
    } finally {
      nrlock.unlock();
    }
  }

  @Benchmark @Threads(4)
  public int nonReentrantLock_contention() {
    nrlock.lock();
    try {
      return counter++;
    } finally {
      nrlock.unlock();
    }
  }
}
