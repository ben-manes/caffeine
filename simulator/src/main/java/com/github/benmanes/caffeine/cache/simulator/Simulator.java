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

import static com.github.benmanes.caffeine.cache.simulator.Simulator.Message.ERROR;
import static com.github.benmanes.caffeine.cache.simulator.Simulator.Message.FINISH;
import static com.github.benmanes.caffeine.cache.simulator.Simulator.Message.START;
import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.parser.TraceFormat;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyActor;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.Registry;
import com.github.benmanes.caffeine.cache.simulator.report.Reporter;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Iterators;
import com.typesafe.config.Config;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.routing.ActorRefRoutee;
import akka.routing.BroadcastRoutingLogic;
import akka.routing.Routee;
import akka.routing.Router;

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
public final class Simulator extends AbstractActor {
  public enum Message { START, FINISH, ERROR }

  private final TraceReader traceReader;
  private final BasicSettings settings;
  private final Stopwatch stopwatch;
  private final Reporter reporter;
  private final Router router;
  private final int batchSize;
  private int remaining;

  public Simulator() {
    Config config = context().system().settings().config().getConfig("caffeine.simulator");
    settings = new BasicSettings(config);
    traceReader = makeTraceReader();

    List<Routee> routes = makeRoutes();
    router = new Router(new BroadcastRoutingLogic(), routes);
    remaining = routes.size();

    batchSize = settings.batchSize();
    stopwatch = Stopwatch.createStarted();
    reporter = settings.report().format().create(config);
  }

  @Override
  public void preStart() {
    self().tell(START, self());
  }

  @Override
  public Receive createReceive() {
    return receiveBuilder()
        .matchEquals(START, msg -> broadcast())
        .matchEquals(ERROR, msg -> context().stop(self()))
        .match(PolicyStats.class, this::reportStats)
        .build();
  }

  /** Broadcast the trace events to all of the policy actors. */
  private void broadcast() {
    if (remaining == 0) {
      context().system().log().error("No active policies in the current configuration");
      context().stop(self());
      return;
    }

    try (Stream<AccessEvent> events = traceReader.events()) {
      Iterators.partition(events.iterator(), batchSize)
          .forEachRemaining(batch -> router.route(batch, self()));
      router.route(FINISH, self());
    } catch (Exception e) {
      context().system().log().error(e, "");
      context().stop(self());
    }
  }

  /** Returns a trace reader for the access events. */
  private TraceReader makeTraceReader() {
    if (settings.isSynthetic()) {
      return Synthetic.generate(settings);
    }
    List<String> filePaths = settings.traceFiles().paths();
    TraceFormat format = settings.traceFiles().format();
    return format.readFiles(filePaths);
  }

  /** Returns the actors to broadcast trace events to. */
  private List<Routee> makeRoutes() {
    return new Registry(settings, traceReader.characteristics()).policies().stream().map(policy -> {
      ActorRef actorRef = context().actorOf(Props.create(PolicyActor.class, policy));
      context().watch(actorRef);
      return new ActorRefRoutee(actorRef);
    }).collect(toList());
  }

  /** Add the stats to the reporter, print if completed, and stop the simulator. */
  private void reportStats(PolicyStats stats) throws IOException {
    reporter.add(stats);
    if (--remaining == 0) {
      reporter.print();
      context().stop(self());
      System.out.println("Executed in " + stopwatch);
    }
  }

  public static void main(String[] args) {
    akka.Main.main(new String[] { Simulator.class.getName() } );
  }
}
