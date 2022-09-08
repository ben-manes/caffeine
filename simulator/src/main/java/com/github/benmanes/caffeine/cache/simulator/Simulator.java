/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.parser.TraceFormat;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyActor;
import com.github.benmanes.caffeine.cache.simulator.policy.Registry;
import com.google.common.base.Stopwatch;
import com.typesafe.config.ConfigFactory;

/**
 * A simulator that broadcasts the recorded cache events to each policy and generates an aggregated
 * report. See <tt>reference.conf</tt> for details on the configuration.
 * <p>
 * The simulator reports the hit rate of each of the policy being evaluated. A miss may occur
 * due to,
 * <ul>
 *   <li>Conflict: multiple entries are mapped to the same location
 *   <li>Compulsory: the first reference misses and the entry must be loaded
 *   <li>Capacity: the cache is not large enough to contain the needed entries
 *   <li>Coherence: an invalidation is issued by another process in the system
 * </ul>
 * <p>
 * It is recommended that multiple access traces are used during evaluation to see how the policies
 * handle different workload patterns. When choosing a policy some metrics that are not reported
 * may be relevant, such as the cost of maintaining the policy's internal structures.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Simulator {
  private final BasicSettings settings;

  public Simulator() {
    settings = new BasicSettings(ConfigFactory.load().getConfig("caffeine.simulator"));
  }

  /** Broadcast the trace events to all of the policy actors. */
  public void run() {
    var trace = getTraceReader(settings);
    var policies = getPolicyActors(trace.characteristics());
    if (policies.isEmpty()) {
      System.err.println("No active policies in the current configuration");
      return;
    }

    var stopwatch = Stopwatch.createStarted();
    try {
      broadcast(policies, trace);
      report(policies, trace.characteristics());
      System.out.println("Executed in " + stopwatch);
    } catch (RuntimeException e) {
      if (!Thread.currentThread().isInterrupted()) {
        throw e;
      }
    }
  }

  private void broadcast(List<PolicyActor> policies, TraceReader trace) {
    long skip = settings.trace().skip();
    long limit = settings.trace().limit();
    int batchSize = settings.actor().batchSize();
    try (Stream<AccessEvent> events = trace.events().skip(skip).limit(limit)) {
      var batch = new ArrayList<AccessEvent>(batchSize);
      events.forEach(event -> {
        batch.add(event);
        if (batch.size() == batchSize) {
          var accessEvents = List.copyOf(batch);
          for (var policy : policies) {
            policy.send(accessEvents);
          }
          batch.clear();
        }
      });

      var accessEvents = List.copyOf(batch);
      for (var policy : policies) {
        policy.send(accessEvents);
      }
      for (var policy : policies) {
        policy.finish();
      }
    }
  }

  private void report(List<PolicyActor> policies, Set<Characteristic> characteristics) {
    var results = policies.stream().map(PolicyActor::stats).collect(toList());
    var reporter = settings.report().format().create(settings.config(), characteristics);
    reporter.print(results);
  }

  /** Returns a trace reader for the access events. */
  private TraceReader getTraceReader(BasicSettings settings) {
    if (settings.trace().isSynthetic()) {
      return Synthetic.generate(settings.trace());
    }
    List<String> filePaths = settings.trace().traceFiles().paths();
    TraceFormat format = settings.trace().traceFiles().format();
    return format.readFiles(filePaths);
  }

  /** Returns the policy actors that asynchronously apply the trace events. */
  private List<PolicyActor> getPolicyActors(Set<Characteristic> characteristics) {
    var registry = new Registry(settings, characteristics);
    return registry.policies().stream()
        .map(policy -> new PolicyActor(Thread.currentThread(), policy, settings))
        .collect(toList());
  }

  public static void main(String[] args) {
    Logger.getLogger("").setLevel(Level.WARNING);
    new Simulator().run();
  }
}
