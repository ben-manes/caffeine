/*
 * Copyright 2023 Ben Manes. All Rights Reserved.
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

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import com.google.common.cache.CacheBuilder;

/**
 * <p>
 * <pre>{@code
 * ./gradlew jmh -PincludePattern=BuilderBenchmark --no-daemon
 * }</pre>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
public class BuilderBenchmark {
  @Param
  BuilderType type;

  Supplier<?> builder;

  @Setup
  public void setup() {
    if (type == BuilderType.Unbound_Caffeine) {
      builder = Caffeine.newBuilder()::build;
    } else if (type == BuilderType.Bounded_Caffeine) {
      builder = Caffeine.newBuilder()
          .expireAfterAccess(Duration.ofMinutes(1))
          .maximumSize(100)
          ::build;
    } else if (type == BuilderType.Unbound_Guava) {
      builder = CacheBuilder.newBuilder()::build;
    } else if (type == BuilderType.Bounded_Guava) {
      builder = CacheBuilder.newBuilder()
          .expireAfterAccess(Duration.ofMinutes(1))
          .maximumSize(100)
          ::build;
    } else if (type == BuilderType.ConcurrentHashMap) {
      builder = ConcurrentHashMap::new;
    } else {
      throw new IllegalStateException();
    }
  }

  @Benchmark
  public void build(Blackhole blackhole) {
    blackhole.consume(builder.get());
  }

  public enum BuilderType {
    Unbound_Caffeine, Bounded_Caffeine,
    Unbound_Guava, Bounded_Guava,
    ConcurrentHashMap;
  }
}
