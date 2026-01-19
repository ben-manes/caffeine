/*
 * Copyright 2026 Ben Manes. All Rights Reserved.
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

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;

import org.testng.IMethodInstance;
import org.testng.IMethodInterceptor;
import org.testng.ITestContext;

import com.google.common.hash.Hashing;
import com.google.common.math.IntMath;

/**
 * An interceptor that partitions the test suite so that only an exclusive subset of methods are run
 * by this shard.
 * <p>
 * The test methods are evenly distributed, but the number of test executions per shard will vary by
 * the number of method invocations, e.g. due to parameterized testing. Method granularity
 * outperforms sharding within the parameterized test generator due to the startup overhead of
 * the filtering the Cartesian product. Also, the multi-threaded tests saturate the host CPUs so if
 * distributed then all shards are penalized, which lowers overall throughput due to starving the
 * Gradle task's parallel forks.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ShardingMethodInterceptor implements IMethodInterceptor {

  @Override
  public List<IMethodInstance> intercept(List<IMethodInstance> methods, ITestContext context) {
    var options = Options.fromSystemProperties();
    if ((options.shardCount() == 1) && !options.isFiltered()) {
      return methods;
    }
    return methods.stream()
        .filter(method -> {
          if (options.isFiltered() && hasCacheSpec(method)) {
            return true;
          }
          int hash = Hashing.farmHashFingerprint64()
              .hashUnencodedChars(method.getMethod().getQualifiedName()).asInt();
          return IntMath.mod(hash, options.shardCount()) == options.shardIndex();
        }).collect(toImmutableList());
  }

  private static boolean hasCacheSpec(IMethodInstance method) {
    return method.getMethod().getConstructorOrMethod()
        .getMethod().isAnnotationPresent(CacheSpec.class);
  }
}
