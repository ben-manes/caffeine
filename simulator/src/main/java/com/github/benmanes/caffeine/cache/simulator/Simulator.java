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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.parser.LogReader;
import com.github.benmanes.caffeine.cache.simulator.parser.lirs.LirsTraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyActor;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyBuilder;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.report.TextReport;
import com.typesafe.config.Config;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.routing.ActorRefRoutee;
import akka.routing.BroadcastRoutingLogic;
import akka.routing.Routee;
import akka.routing.Router;

/**
 * The simulator broadcasts the recorded cache events to each policy actor and generates an
 * aggregated report.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Simulator extends UntypedActor {
  public enum Message { START, FINISH }

  private final TextReport report;
  private final Config config;
  private final Router router;
  private int remaining;

  public Simulator() {
    config = getContext().system().settings().config().getConfig("caffeine.simulator");

    List<Routee> routes = makeRoutes();
    router = new Router(new BroadcastRoutingLogic(), routes);
    remaining = routes.size();
    report = new TextReport();

    getSelf().tell(Message.START, ActorRef.noSender());
  }

  @Override
  public void onReceive(Object msg) throws IOException {
    if (msg == Message.START) {
      try (Stream<?> events = eventStream()) {
        events.forEach(event -> router.route(event, getSelf()));
      }
      router.route(Message.FINISH, getSelf());
    } else if (msg instanceof PolicyStats) {
      report.add((PolicyStats) msg);
      if (--remaining == 0) {
        report.writeTo(System.out);
        getContext().stop(getSelf());
      }
    }
  }

  private Stream<?> eventStream() throws IOException {
    BasicSettings settings = new BasicSettings(config);
    if (settings.isSynthetic()) {
      return Synthetic.generate(settings);
    }
    Path filePath = settings.fileSource().path();
    switch (settings.fileSource().format()) {
      case CAFFEINE_TEXT:
        return LogReader.textLogStream(filePath);
      case CAFFEINE_BINARY:
        return LogReader.binaryLogStream(filePath);
      case LIRS:
        return LirsTraceReader.traceStream(filePath);
      default:
        throw new IllegalStateException("Unknown format: " + settings.fileSource().format());
    }
  }

  private List<Routee> makeRoutes() {
    BasicSettings settings = new BasicSettings(config);
    Map<String, Policy> policies = new TreeMap<>();
    for (String policyType : settings.policies()) {
      for (String admittorType : settings.admission().admittors()) {
        Policy policy = new PolicyBuilder(config)
            .admittor(admittorType)
            .type(policyType)
            .build();
        policies.put(policy.stats().name(), policy);
      }
    }
    return policies.values().stream().map(policy -> {
      ActorRef actorRef = getContext().actorOf(Props.create(PolicyActor.class, policy));
      getContext().watch(actorRef);
      return new ActorRefRoutee(actorRef);
    }).collect(Collectors.toList());
  }

  public static void main(String[] args) {
    akka.Main.main(new String[] { Simulator.class.getName() } );
  }
}
