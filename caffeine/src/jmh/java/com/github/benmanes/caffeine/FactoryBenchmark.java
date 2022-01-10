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

  @Benchmark
  public Alpha direct() {
    return new Alpha();
  }

  @Benchmark
  public Alpha methodHandle_invoke() {
    return methodHandleFactory.invoke();
  }

  @Benchmark
  public Alpha methodHandle_invokeExact() {
    return methodHandleFactory.invokeExact();
  }

  @Benchmark
  public Alpha reflection() {
    return reflectionFactory.newInstance();
  }

  static final class MethodHandleFactory {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodType METHOD_TYPE = MethodType.methodType(void.class);

    private final MethodHandle methodHandle;

    MethodHandleFactory() {
      try {
        methodHandle = LOOKUP.findConstructor(Alpha.class, METHOD_TYPE);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    Alpha invoke() {
      try {
        return (Alpha) methodHandle.invoke();
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    Alpha invokeExact() {
      try {
        return (Alpha) methodHandle.invokeExact();
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }
  }

  static final class ReflectionFactory {
    private final Constructor<Alpha> constructor;

    ReflectionFactory() {
      try {
        constructor = Alpha.class.getConstructor();
      } catch (NoSuchMethodException | SecurityException e) {
        throw new RuntimeException(e);
      }
    }

    Alpha newInstance() {
      try {
        return constructor.newInstance();
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }
  }

  static final class Alpha {
    public Alpha() {}
  }
}
