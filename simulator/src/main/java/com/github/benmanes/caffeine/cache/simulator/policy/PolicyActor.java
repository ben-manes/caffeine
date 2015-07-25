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
package com.github.benmanes.caffeine.cache.simulator.policy;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.github.benmanes.caffeine.cache.simulator.Simulator.Message;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.dispatch.BoundedMessageQueueSemantics;
import akka.dispatch.RequiresMessageQueue;

/**
 * An actor that proxies to the page replacement policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class PolicyActor extends UntypedActor
    implements RequiresMessageQueue<BoundedMessageQueueSemantics> {
  private final Policy policy;

  public PolicyActor(Policy policy) {
    this.policy = requireNonNull(policy);
  }

  @Override
  public void onReceive(Object msg) throws Exception {
    if (msg instanceof List) {
      @SuppressWarnings("unchecked")
      List<Object> events = (List<Object>) msg;
      events.forEach(this::process);
    } else if (msg == Message.FINISH) {
      getSender().tell(policy.stats(), ActorRef.noSender());
      getContext().stop(getSelf());
    } else {
      context().system().log().error("Invalid message: " + msg);
    }
  }

  private void process(Object o) {
    policy.stats().stopwatch().start();
    try {
      policy.record(o);
    } catch (Exception e) {
      context().system().log().error(e, "");
    } finally {
      policy.stats().stopwatch().stop();
    }
  }
}
