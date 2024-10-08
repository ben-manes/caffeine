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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Threads;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings({"MemberName", "PMD.MethodNamingConventions"})
public class TimeBenchmark {

  @Benchmark @Threads(1)
  public long nanos_noContention() {
    return System.nanoTime();
  }

  @Benchmark @Threads(8)
  public long nanos_contention() {
    return System.nanoTime();
  }

  @Benchmark @Threads(1)
  public long millis_noContention() {
    return System.currentTimeMillis();
  }

  @Benchmark @Threads(8)
  public long millis_contention() {
    return System.currentTimeMillis();
  }
}
