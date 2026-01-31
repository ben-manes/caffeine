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

import static org.junit.jupiter.api.extension.ConditionEvaluationResult.disabled;
import static org.junit.jupiter.api.extension.ConditionEvaluationResult.enabled;
import static org.junit.platform.commons.support.AnnotationSupport.isAnnotated;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.google.common.hash.Hashing;
import com.google.common.math.IntMath;

/**
 * An interceptor that partitions the test suite so that this shard runs an exclusive subset of
 * methods.
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
@SuppressWarnings("unused")
public final class ShardedTestExtension implements ExecutionCondition {
  private static final ConcurrentMap<String, Boolean> IS_MATCH = new ConcurrentHashMap<>(10_000);
  private static final ConditionEvaluationResult MATCHING_SHARD = enabled("Matching sharded");
  private static final ConditionEvaluationResult WRONG_SHARDED = disabled("Incorrect shard");
  private static final ConditionEvaluationResult NOT_SHARDED = enabled("Not sharded");
  private static final ConditionEvaluationResult NOT_METHOD = enabled("Not method");
  private static final ConditionEvaluationResult FILTERED = disabled("Filtered");

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    var options = Options.fromSystemProperties();
    if ((options.shardCount() == 1) && !options.isFiltered()) {
      return NOT_SHARDED;
    } else if (context.getTestMethod().isEmpty()) {
      return NOT_METHOD;
    } else if (options.isFiltered()) {
      return hasCacheSpec(context) ? MATCHING_SHARD : FILTERED;
    }
    return isMatchingShard(context) ? MATCHING_SHARD : WRONG_SHARDED;
  }

  private static boolean isMatchingShard(ExtensionContext context) {
    var name = context.getRequiredTestClass().getCanonicalName()
        + "." + context.getRequiredTestMethod().getName();
    return IS_MATCH.computeIfAbsent(name, test -> {
      var options = Options.fromSystemProperties();
      int hash = Hashing.farmHashFingerprint64().hashUnencodedChars(test).asInt();
      int index = IntMath.mod(hash, options.shardCount());
      return (index == options.shardIndex());
    });
  }

  private static boolean hasCacheSpec(ExtensionContext extension) {
    return isAnnotated(extension.getTestMethod(), CacheSpec.class)
        || isAnnotated(extension.getTestClass(), CacheSpec.class);
  }
}
