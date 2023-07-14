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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Locale.US;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.typesafe.config.Config;
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

  public Simulator(Config config) {
    settings = new BasicSettings(config.getConfig("caffeine.simulator"));
  }

  /** Broadcast the trace events to all of the policy actors. */
  public void run() {
    var trace = getTraceReader(settings);
    var policies = getPolicyActors(trace.characteristics());
    if (policies.isEmpty()) {
      System.err.println("No active policies in the current configuration");
      return;
    }

    try {
      broadcast(trace, policies);
      report(trace, policies);
    } catch (RuntimeException e) {
      throwError(e, policies);
    }
  }

  private void broadcast(TraceReader trace, List<PolicyActor> policies) {
    long skip = settings.trace().skip();
    long limit = settings.trace().limit();
    int batchSize = settings.actor().batchSize();
    try (Stream<AccessEvent> events = trace.events().skip(skip).limit(limit)) {
      var batch = new ArrayList<AccessEvent>(batchSize);
      events.forEach(event -> {
        batch.add(event);
        if (batch.size() == batchSize) {
          var accessEvents = ImmutableList.copyOf(batch);
          for (var policy : policies) {
            policy.send(accessEvents);
          }
          batch.clear();
        }
      });

      var remainder = ImmutableList.copyOf(batch);
      for (var policy : policies) {
        policy.send(remainder);
        policy.finish();
      }

      var futures = policies.stream()
          .map(PolicyActor::completed)
          .toArray(CompletableFuture<?>[]::new);
      CompletableFuture.allOf(futures).join();
    }
  }

  private void report(TraceReader trace, List<PolicyActor> policies) {
    var reporter = settings.report().format().create(settings.config(), trace.characteristics());
    var results = policies.stream().map(PolicyActor::stats).collect(toImmutableList());
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
  private ImmutableList<PolicyActor> getPolicyActors(Set<Characteristic> characteristics) {
    var registry = new Registry(settings, characteristics);
    return registry.policies().stream()
        .map(policy -> new PolicyActor(Thread.currentThread(), policy, settings))
        .collect(toImmutableList());
  }

  /** Throws the underlying cause for the simulation failure. */
  private void throwError(RuntimeException error, Iterable<PolicyActor> policies) {
    if (!Thread.currentThread().isInterrupted()) {
      throw error;
    }
    for (var policy : policies) {
      if (policy.completed().isCompletedExceptionally()) {
        try {
          policy.completed().join();
        } catch (CompletionException e) {
          Throwables.throwIfUnchecked(e.getCause());
          e.addSuppressed(error);
          throw e;
        }
      }
    }
    throw error;
  }

  public static void main(String[] args) {
    Logger.getLogger("").setLevel(Level.WARNING);
    var simulator = new Simulator(ConfigFactory.load());
    var stopwatch = Stopwatch.createStarted();
    simulator.run();
    System.out.printf(US, "Executed in %s%n", stopwatch);
  }
}
