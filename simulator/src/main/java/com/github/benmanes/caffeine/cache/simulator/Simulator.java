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

import static com.github.benmanes.caffeine.cache.simulator.admission.Admission.CLAIRVOYANT;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Locale.US;
import static java.util.stream.Gatherers.windowFixed;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.parser.ClairvoyantTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyActor;
import com.github.benmanes.caffeine.cache.simulator.policy.Registry;
import com.github.benmanes.caffeine.cache.simulator.policy.opt.ClairvoyantPolicy;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.MustBeClosed;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * A simulator that broadcasts the recorded cache events to each policy and generates an aggregated
 * report. See <code>reference.conf</code> for details on the configuration.
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
    try (var trace = getTraceReader(settings)) {
      var policies = getPolicyActors(trace);
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
  }

  private void broadcast(TraceReader trace, List<PolicyActor> policies) {
    int batchSize = settings.actor().batchSize();
    try (Stream<AccessEvent> events = window(trace)) {
      events.gather(windowFixed(batchSize)).forEachOrdered(batch -> {
        for (var policy : policies) {
          policy.send(batch);
        }
      });
      var futures = policies.stream().map(policy -> {
        policy.finish();
        return policy.completed();
      }).toArray(CompletableFuture<?>[]::new);
      CompletableFuture.allOf(futures).join();
    }
  }

  private void report(TraceReader trace, List<PolicyActor> policies) {
    var reporter = settings.report().format().create(settings.config(), trace.characteristics());
    var results = policies.stream().map(PolicyActor::stats).collect(toImmutableList());
    reporter.print(results);
  }

  /** Returns the trace events, windowed by the configured skip and limit. */
  @MustBeClosed
  private Stream<AccessEvent> window(TraceReader trace) {
    // The clairvoyant reader materializes the windowed range, so it must not be re-applied
    return (trace instanceof ClairvoyantTraceReader)
        ? trace.events()
        : trace.events().skip(settings.trace().skip()).limit(settings.trace().limit());
  }

  /** Returns a trace reader for the access events. */
  private static TraceReader getTraceReader(BasicSettings settings) {
    TraceReader trace = settings.trace().isSynthetic()
        ? Synthetic.generate(settings.trace())
        : settings.trace().traceFiles().format().readFiles(settings.trace().traceFiles().paths());
    return isClairvoyant(settings)
        ? new ClairvoyantTraceReader(trace, settings.trace().skip(), settings.trace().limit())
        : trace;
  }

  /** Returns whether any configured policy or admitter needs the clairvoyant look-ahead. */
  private static boolean isClairvoyant(BasicSettings settings) {
    var clairvoyant = ClairvoyantPolicy.class.getAnnotation(PolicySpec.class).name();
    return settings.admission().contains(CLAIRVOYANT)
        || settings.policies().stream().anyMatch(clairvoyant::equalsIgnoreCase);
  }

  /**
   * Returns the policy actors that asynchronously apply the trace events. A clairvoyant reader is
   * installed for the scope of their construction so that its policies/admitters can take a cursor.
   */
  private ImmutableList<PolicyActor> getPolicyActors(TraceReader trace) {
    var characteristics = trace.characteristics();
    return (trace instanceof ClairvoyantTraceReader reader)
        ? reader.scoped(() -> buildPolicyActors(characteristics))
        : buildPolicyActors(characteristics);
  }

  private ImmutableList<PolicyActor> buildPolicyActors(Set<Characteristic> characteristics) {
    var registry = new Registry(settings, characteristics);
    return registry.policies().stream()
        .map(policy -> new PolicyActor(Thread.currentThread(), policy, settings))
        .collect(toImmutableList());
  }

  /** Throws the underlying cause for the simulation failure. */
  private static void throwError(RuntimeException error, Iterable<PolicyActor> policies) {
    if (!Thread.currentThread().isInterrupted()) {
      throw error;
    }
    for (var policy : policies) {
      if (policy.completed().isCompletedExceptionally()) {
        try {
          policy.completed().join();
        } catch (CompletionException e) {
          if (e.getCause() != null) {
            Throwables.throwIfUnchecked(e.getCause());
          }
          e.addSuppressed(error);
          throw e;
        }
      }
    }
    throw error;
  }

  static void main() {
    Logger.getLogger("").setLevel(Level.WARNING);
    var simulator = new Simulator(ConfigFactory.load());
    var stopwatch = Stopwatch.createStarted();
    simulator.run();
    System.out.printf(US, "Executed in %s%n", stopwatch);
  }
}
