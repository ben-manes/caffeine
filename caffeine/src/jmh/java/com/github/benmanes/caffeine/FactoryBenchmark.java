/*
 * Copyright 2017 Ben Manes. All Rights Reserved.
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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
public class FactoryBenchmark {
  private final ReflectionFactory reflectionFactory = new ReflectionFactory();
  private final MethodHandleFactory methodHandleFactory = new MethodHandleFactory();

  @State(Scope.Thread)
  public static class ThreadState {
    int i;
  }

  @Benchmark
  public Alpha direct(ThreadState state) {
    return new Alpha(state.i++);
  }

  @Benchmark
  public Alpha methodHandle_invoke(ThreadState state) {
    return methodHandleFactory.invoke(state.i++);
  }

  @Benchmark
  public Alpha methodHandle_invokeExact(ThreadState state) {
    return methodHandleFactory.invokeExact(state.i++);
  }

  @Benchmark
  public Alpha reflection(ThreadState state) {
    return reflectionFactory.newInstance(state.i++);
  }

  static final class MethodHandleFactory {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodType METHOD_TYPE = MethodType.methodType(void.class, int.class);

    private final MethodHandle methodHandle;

    MethodHandleFactory() {
      try {
        methodHandle = LOOKUP.findConstructor(Alpha.class, METHOD_TYPE);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    Alpha invoke(int x) {
      try {
        return (Alpha) methodHandle.invoke(x);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    Alpha invokeExact(int x) {
      try {
        return (Alpha) methodHandle.invokeExact(x);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }
  }

  static final class ReflectionFactory {
    private final Constructor<Alpha> constructor;

    ReflectionFactory() {
      try {
        constructor = Alpha.class.getConstructor(int.class);
      } catch (NoSuchMethodException | SecurityException e) {
        throw new RuntimeException(e);
      }
    }

    Alpha newInstance(int x) {
      try {
        return constructor.newInstance(x);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }
  }

  static final class Alpha {
    @SuppressWarnings("unused")
    private final int x;

    public Alpha(int x) {
      this.x = x;
    }
  }
}
