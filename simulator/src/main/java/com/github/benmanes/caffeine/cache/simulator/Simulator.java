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

import java.util.ArrayList;
import java.util.List;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.routing.ActorRefRoutee;
import akka.routing.BroadcastRoutingLogic;
import akka.routing.Routee;
import akka.routing.Router;

import com.github.benmanes.caffeine.cache.simulator.policy.classic.Lru;
import com.github.benmanes.caffeine.cache.tracing.CacheEvent;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Simulator extends UntypedActor {
  public enum Message { DONE, START }

  Router router;
  int remaining;

  @Override
  public void preStart() {
    List<Routee> routes = new ArrayList<>();
    ActorRef ref = getContext().actorOf(Props.create(Lru.class), "lru");
    getContext().watch(ref);
    routes.add(new ActorRefRoutee(ref));
    router = new Router(new BroadcastRoutingLogic(), routes);
    remaining++;

    getSelf().tell(Message.START, ActorRef.noSender());
  }

  @Override
  public void onReceive(Object msg) throws Exception {
    if (msg == Message.START) {
      router.route(new CacheEvent(), getSelf());
      router.route(Message.DONE, getSelf());
    } else if ((msg == Message.DONE) && (--remaining == 0)) {
      getContext().stop(getSelf());
    }
  }

  public static void main(String[] args) {
    akka.Main.main(new String[] { Simulator.class.getName() } );
  }
}
