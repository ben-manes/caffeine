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

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.apache.commons.lang3.mutable.MutableObject;

import com.github.benmanes.caffeine.cache.simulator.parser.TraceFormat;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyActor;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyActor.AccessEvents;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyActor.Finished;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.Registry;
import com.github.benmanes.caffeine.cache.simulator.report.Reporter;
import com.google.common.base.Stopwatch;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.ChildFailed;
import akka.actor.typed.MailboxSelector;
import akka.actor.typed.Terminated;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

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
public final class Simulator extends AbstractBehavior<Simulator.Command> {
  private final List<ActorRef<PolicyActor.Command>> policies;
  private final TraceReader traceReader;
  private final BasicSettings settings;
  private final Stopwatch stopwatch;
  private final Reporter reporter;

  public Simulator(ActorContext<Command> context, BasicSettings settings) {
    super(context);
    this.settings = settings;
    this.policies = new ArrayList<>();
    this.stopwatch = Stopwatch.createUnstarted();
    this.traceReader = makeTraceReader(settings);
    this.reporter = settings.report().format()
        .create(settings.config(), traceReader.characteristics());
  }

  public static Behavior<Command> create() {
    return Behaviors.setup(context -> {
      var config = context.getSystem().settings().config().getConfig("caffeine.simulator");
      return new Simulator(context, new BasicSettings(config));
    });
  }

  @Override
  public Receive<Command> createReceive() {
    return newReceiveBuilder()
        .onMessage(Broadcast.class, msg -> broadcast())
        .onMessage(Stats.class, msg -> reportStats(msg.policyStats))
        .onSignal(ChildFailed.class, msg -> Behaviors.stopped())
        .onSignal(Terminated.class, msg -> Behaviors.same())
        .build();
  }

  /** Broadcast the trace events to all of the policy actors. */
  private Behavior<Command> broadcast() {
    spawnPolicyActors();
    if (policies.isEmpty()) {
      System.err.println("No active policies in the current configuration");
      return Behaviors.stopped();
    }

    stopwatch.start();
    long skip = settings.trace().skip();
    long limit = settings.trace().limit();
    int batchSize = settings.batchSize();
    try (Stream<AccessEvent> events = traceReader.events().skip(skip).limit(limit)) {
      var batch = new MutableObject<>(new ArrayList<AccessEvent>(batchSize));
      events.forEach(event -> {
        batch.getValue().add(event);
        if (batch.getValue().size() == batchSize) {
          route(new AccessEvents(batch.getValue()));
          batch.setValue(new ArrayList<>(batchSize));
        }
      });
      route(new AccessEvents(batch.getValue()));
      route(new Finished());
      return this;
    }
  }

  /** Publishes the message to all of the policy actors. */
  private void route(PolicyActor.Command message) {
    for (var policy : policies) {
      policy.tell(message);
    }
  }

  /** Returns a trace reader for the access events. */
  private static TraceReader makeTraceReader(BasicSettings settings) {
    if (settings.trace().isSynthetic()) {
      return Synthetic.generate(settings.trace());
    }
    List<String> filePaths = settings.trace().traceFiles().paths();
    TraceFormat format = settings.trace().traceFiles().format();
    return format.readFiles(filePaths);
  }

  /** Spawns the policy actors to broadcast trace events to. */
  private void spawnPolicyActors() {
    var registry = new Registry(settings, traceReader.characteristics());
    var mailbox = MailboxSelector.fromConfig("caffeine.simulator.mailbox");
    for (var policy : registry.policies()) {
      var name = policy.getClass().getSimpleName() + "@" + System.identityHashCode(policy);
      var actor = PolicyActor.create(getContext().getSelf(), policy);
      var actorRef = getContext().spawn(actor, name, mailbox);
      getContext().watch(actorRef);
      policies.add(actorRef);
    }
  }

  /** Add the stats to the reporter, print if completed, and stop the simulator. */
  private Behavior<Command> reportStats(PolicyStats stats) throws IOException {
    reporter.add(stats);

    if (reporter.stats().size() == policies.size()) {
      reporter.print();
      System.out.println("Executed in " + stopwatch);
      return Behaviors.stopped();
    }
    return this;
  }

  public static void main(String[] args) {
    Logger.getLogger("").setLevel(Level.WARNING);
    var simulator = ActorSystem.create(Simulator.create(), "Simulator");
    simulator.tell(new Broadcast());
  }

  /** A message sent to the simulator. */
  @SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
  public abstract static class Command {
    private Command() {}
  }
  private static final class Broadcast extends Command {}
  public static final class Stats extends Command {
    public final PolicyStats policyStats;
    public Stats(PolicyStats policyStats) {
      this.policyStats = requireNonNull(policyStats);
    }
  }
}
