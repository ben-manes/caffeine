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

import static org.junit.platform.engine.FilterResult.includedIf;

import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

import com.google.common.hash.Hashing;
import com.google.common.math.IntMath;

/**
 * A filter that partitions the test suite so that this shard runs an exclusive subset of methods.
 * <p>
 * The test methods are evenly distributed, but the number of test executions per shard varies
 * based on the number of method invocations (e.g., due to parameterized testing). Method-level
 * granularity outperforms sharding within the parameterized test generator due to the startup
 * overhead of filtering the Cartesian product. Additionally, multithreaded tests saturate the host
 * CPUs; when distributed across shards, all shards are penalized, lowering overall throughput by
 * starving the Gradle task's parallel forks.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ShardedTestFilter implements PostDiscoveryFilter {

  @Override
  public FilterResult apply(TestDescriptor descriptor) {
    var options = Options.fromSystemProperties();
    if ((options.shardCount() == 1) && !options.isFiltered()) {
      return includedIf(true);
    }
    var source = descriptor.getSource().orElse(null);
    if (!(source instanceof MethodSource)) {
      return includedIf(true);
    }
    var methodSource = (MethodSource) source;
    return options.isFiltered()
        ? includedIf(hasCacheSpec(methodSource))
        : includedIf(isMatchingShard(methodSource, options));
  }

  private static boolean hasCacheSpec(MethodSource source) {
    var method = source.getJavaMethod();
    return method.isAnnotationPresent(CacheSpec.class)
        || method.getDeclaringClass().isAnnotationPresent(CacheSpec.class);
  }

  private static boolean isMatchingShard(MethodSource source, Options options) {
    int hash = Hashing.farmHashFingerprint64().hashUnencodedChars(source.toString()).asInt();
    int index = IntMath.mod(hash, options.shardCount());
    return (index == options.shardIndex());
  }
}
