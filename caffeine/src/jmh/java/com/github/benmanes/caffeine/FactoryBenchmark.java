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

import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * This benchmark can be run by optionally specifying the target jvm in the command.
 * <p>
 * {@snippet lang="shell" :
 * ./gradlew jmh -PincludePattern=FactoryBenchmark --rerun
 * }
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
@SuppressWarnings({"MemberName", "PMD.MethodNamingConventions"})
public class FactoryBenchmark {
  private final ReflectionFactory reflectionFactory = new ReflectionFactory();
  private final MethodHandleFactory methodHandleFactory = new MethodHandleFactory();

  @Benchmark
  public void direct(Blackhole blackhole) {
    blackhole.consume(new Alpha());
  }

  @Benchmark
  public void methodHandle_invoke(Blackhole blackhole) {
    blackhole.consume(methodHandleFactory.invoke());
  }

  @Benchmark
  public void methodHandle_invokeExact(Blackhole blackhole) {
    blackhole.consume(methodHandleFactory.invokeExact());
  }

  @Benchmark
  public void methodHandle_lambda(Blackhole blackhole) {
    blackhole.consume(methodHandleFactory.lambda());
  }

  @Benchmark
  public void reflection(Blackhole blackhole) {
    blackhole.consume(reflectionFactory.newInstance());
  }

  static final class MethodHandleFactory {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodType METHOD_TYPE = MethodType.methodType(void.class);

    private final MethodHandle methodHandle;
    private final AlphaConstructor lambda;

    MethodHandleFactory() {
      try {
        methodHandle = LOOKUP.findConstructor(Alpha.class, METHOD_TYPE);
        lambda = (AlphaConstructor) LambdaMetafactory
            .metafactory(LOOKUP, "construct", MethodType.methodType(AlphaConstructor.class),
                methodHandle.type(), methodHandle, methodHandle.type())
            .getTarget().invokeExact();
      } catch (Throwable e) {
        throw new IllegalStateException(e);
      }
    }

    Alpha invoke() {
      try {
        return (Alpha) methodHandle.invoke();
      } catch (Throwable e) {
        throw new IllegalStateException(e);
      }
    }

    Alpha invokeExact() {
      try {
        return (Alpha) methodHandle.invokeExact();
      } catch (Throwable e) {
        throw new IllegalStateException(e);
      }
    }

    Alpha lambda() {
      return lambda.construct();
    }
  }

  static final class ReflectionFactory {
    private final Constructor<Alpha> constructor;

    ReflectionFactory() {
      try {
        constructor = Alpha.class.getConstructor();
      } catch (NoSuchMethodException | SecurityException e) {
        throw new IllegalStateException(e);
      }
    }

    Alpha newInstance() {
      try {
        return constructor.newInstance();
      } catch (Throwable e) {
        throw new IllegalStateException(e);
      }
    }
  }

  static final class Alpha {
    public Alpha() {}
  }

  @FunctionalInterface
  private interface AlphaConstructor {
    Alpha construct();
  }
}
