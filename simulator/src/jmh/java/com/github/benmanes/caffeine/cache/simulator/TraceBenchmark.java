/*
 * Copyright 2018 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator;

import static java.util.stream.Collectors.toList;
import static org.openjdk.jmh.annotations.Scope.Benchmark;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import com.github.benmanes.caffeine.cache.simulator.parser.TraceFormat;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Registry;
import com.google.common.collect.ImmutableSet;
import com.typesafe.config.ConfigFactory;

/**
 * A benchmark to show the feasibility of a cache policy on a fixed workload. This is not meant to
 * be comparative, but rather simulate the benefits of a higher hit rate if the implementation costs
 * are not excessive.
 * <p>
 * See JMHSample_38_PerInvokeSetup for background on the per-iteration setup.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Benchmark)
public class TraceBenchmark {
  @Param({"linked.Lru", "sketch.WindowTinyLfu"})
  String policyName;

  @Param({"0", "1000"})
  int missPenalty;

  AccessEvent[] events;
  Registry registry;

  @Setup
  public void setup() throws IOException {
    BasicSettings settings = new BasicSettings(
        ConfigFactory.load().getConfig("caffeine.simulator"));
    events = readEventStream(settings).toArray(AccessEvent[]::new);
    registry = new Registry(settings, ImmutableSet.of());
  }

  @Benchmark
  public Policy trace() {
    Policy policy = makePolicy();
    for (AccessEvent event : events) {
      policy.record(event);
    }
    Blackhole.consumeCPU(missPenalty * policy.stats().missCount());
    return policy;
  }

  public Policy makePolicy() {
    Set<Policy> policies = registry.policy(policyName);
    if (policies.size() > 1) {
      throw new IllegalArgumentException("Use one variation per policy configuration: "
          + policies.stream().map(policy -> policy.stats().name()).collect(toList()));
    }
    return policies.iterator().next();
  }

  private Stream<AccessEvent> readEventStream(BasicSettings settings) throws IOException {
    if (settings.trace().isSynthetic()) {
      return Synthetic.generate(settings.trace()).events();
    }
    List<String> filePaths = settings.trace().traceFiles().paths();
    TraceFormat format = settings.trace().traceFiles().format();
    return format.readFiles(filePaths).events();
  }
}
