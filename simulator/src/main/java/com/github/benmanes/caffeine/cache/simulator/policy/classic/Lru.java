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
package com.github.benmanes.caffeine.cache.simulator.policy.classic;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;

import com.github.benmanes.caffeine.cache.simulator.Simulator.Message;
import com.github.benmanes.caffeine.cache.tracing.CacheEvent;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Lru extends UntypedActor {
  private final BoundedLinkedHashMap cache = BoundedLinkedHashMap.asLru(100);

  @Override
  public void onReceive(Object msg) throws Exception {
    if (msg instanceof CacheEvent) {
      cache.handleEvent((CacheEvent) msg);
    } else if (msg == Message.DONE) {
      getSender().tell(Message.DONE, ActorRef.noSender());
      getContext().stop(getSelf());
    }
    System.out.println("LRU: " + msg);
  }
}
