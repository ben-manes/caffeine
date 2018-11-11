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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import com.google.common.collect.ForwardingMap;

/**
 * Demonstrates that inheritance is ~8% faster than delegation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
public class DelegationBenchmark {
  private static final int SIZE = (2 << 14);
  private static final int MASK = SIZE - 1;

  final Map<Integer, Integer> inherit = new InheritMap();
  final Map<Integer, Integer> delegate = new DelegateMap();

  @Setup
  public void setup() {
    for (int i = 0; i < SIZE; i++) {
      inherit.put(i, i);
      delegate.put(i, i);
    }
  }

  @State(Scope.Thread)
  public static class ThreadState {
    int index = ThreadLocalRandom.current().nextInt();
  }

  @Benchmark
  public Integer inherit_get(ThreadState threadState) {
    return inherit.get(threadState.index++ & MASK);
  }

  @Benchmark
  public Integer delegate_get(ThreadState threadState) {
    return delegate.get(threadState.index++ & MASK);
  }

  static final class InheritMap extends ConcurrentHashMap<Integer, Integer> {
    private static final long serialVersionUID = 1L;

    @Override
    public Integer get(Object key) {
      Integer value = super.get(key);
      return (value == null) ? null : (value - 1);
    }
  }

  static final class DelegateMap extends ForwardingMap<Integer, Integer> {
    final Map<Integer, Integer> delegate = new ConcurrentHashMap<>();

    @Override
    public Integer get(Object key) {
      Integer value = delegate.get(key);
      return (value == null) ? null : (value - 1);
    }

    @Override
    protected Map<Integer, Integer> delegate() {
      return delegate;
    }
  }
}
