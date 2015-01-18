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
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.joor.Reflect;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.routing.ActorRefRoutee;
import akka.routing.BroadcastRoutingLogic;
import akka.routing.Routee;
import akka.routing.Router;

import com.github.benmanes.caffeine.cache.simulator.parser.LogReader;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.tracing.CacheEvent;

/**
 * The simulator broadcasts the recorded cache events to each policy actor and generates an
 * aggregated report.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Simulator extends UntypedActor {
  public enum Message { DONE, START }

  private final BasicSettings settings;
  private final Router router;
  private int remaining;

  public Simulator() {
    settings = new BasicSettings(this);
    remaining = settings.policies().size();
    router = makeBroadcastingRouter();

    getSelf().tell(Message.START, ActorRef.noSender());
  }

  @Override
  public void onReceive(Object msg) throws IOException {
    if (msg == Message.START) {
      getEvents().forEach(event -> router.route(event, getSelf()));
      router.route(Message.DONE, getSelf());
    } else if (msg instanceof PolicyStats) {
      PolicyStats result = (PolicyStats) msg;
      System.out.printf("Policy: %s, hit rate: %.2f%%, evictions: %,d%n",
          result.name(), 100 * result.hitRate(), result.evictionCount());
      if (--remaining == 0) {
        getContext().stop(getSelf());
      }
    }
  }

  private Stream<CacheEvent> getEvents() throws IOException {
    if (settings.isFile()) {
      return LogReader.textLogStream(Files.newBufferedReader(settings.fileSource().path()));
    }

    int items = settings.synthetic().items();
    switch (settings.synthetic().distribution()) {
      case "counter":
        return Synthetic.counter(items);
      case "zipfian":
        return Synthetic.zipfian(items);
      case "scrambledZipfian":
        return Synthetic.scrambledZipfian(items);
      default:
        throw new IllegalStateException("Unknown distribution: " +
            settings.synthetic().distribution());
    }
  }

  private Router makeBroadcastingRouter() {
    String packageName = getClass().getPackage().getName();
    List<Routee> routes = settings.policies().stream().map(policy -> {
      Class<?> actorClass = Reflect.on(packageName + ".policy." + policy).type();
      ActorRef actorRef = getContext().actorOf(Props.create(actorClass, policy), policy);
      getContext().watch(actorRef);
      return new ActorRefRoutee(actorRef);
    }).collect(Collectors.toList());
    return new Router(new BroadcastRoutingLogic(), routes);
  }

  public static void main(String[] args) {
    akka.Main.main(new String[] { Simulator.class.getName() } );
  }
}
