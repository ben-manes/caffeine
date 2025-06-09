/*
 * Copyright 2025 Ben Manes. All Rights Reserved.
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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import org.jspecify.annotations.Nullable;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.L_Result;

import com.google.errorprone.annotations.Var;

/**
 * A stress test that simulates the behavior for {@link java.lang.ref.Reference} reads and writes in
 * weak or soft valued caches. The {@link Node#setValue} proactively clears the underlying referent
 * after updating the entry's value. This is done to avoid cross-generational pollution which can
 * cause garbage collectors to unnecessarily promote young dead objects to an old collection and
 * increase pause times. The {@link Node#getValue} compensates by a re-check validation to determine
 * if the observed null referent is due to garbage collection or a stale read. Due to referent being
 * read and written with plain memory semantics, an additional memory barrier is required to ensure
 * the correct visibility ordering.
 * <p>
 * {@snippet lang="shell" :
 * # use JAVA_VERSION for an alternative jdk
 * JAVA_VERSION=11 ./gradlew caffeine:jcstress --tests IntermittentNull --rerun
 * }
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
public final class IntermittentNull {

  private IntermittentNull() {}

  @State
  @JCStressTest
  @Outcome(id = "ok", expect = Expect.ACCEPTABLE, desc = "Reference seen and valid")
  @Outcome(id = "null", expect = Expect.FORBIDDEN, desc = "Reference seen but field not visible")
  public static class Simple {
    private static final VarHandle VALUE;

    volatile Value value = new Value("ok");

    @Actor
    public void writer() {
      var oldValue = (Value) VALUE.getAcquire(this);
      var newValue = new Value("ok");
      VALUE.setRelease(this, newValue);
      VarHandle.storeStoreFence();
      oldValue.data = null;
    }

    @Actor
    public void reader(L_Result r) {
      @Var var value = (Value) VALUE.getAcquire(this);
      for (;;) {
        var data = value.data;
        if (data != null) {
          r.r1 = data;
          return;
        }
        VarHandle.loadLoadFence();
        var current = (Value) VALUE.getAcquire(this);
        if (value == current) {
          r.r1 = null;
          return;
        }
        value = current;
      }
    }

    static {
      try {
        VALUE = MethodHandles.lookup().findVarHandle(Simple.class, "value", Value.class);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    static final class Value {
      @Nullable String data;

      Value(String data) {
        this.data = data;
      }
    }
  }

  @State
  @JCStressTest
  @Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "Reference seen and valid")
  @Outcome(expect = Expect.FORBIDDEN, desc = "Reference seen but field not visible")
  public static class Actual {
    private static final String OK = "ok";

    final Cache<String, String> cache;

    public Actual() {
      cache = Caffeine.newBuilder().weakValues().build();
      cache.put(OK, OK);
    }

    @Actor
    public void writer(II_Result r) {
      r.r1 = (cache.asMap().put(OK, OK) == null) ? 0 : 1;
    }

    @Actor
    public void reader(II_Result r) {
      r.r2 = (cache.getIfPresent(OK) == null) ? 0 : 1;
    }
  }
}
