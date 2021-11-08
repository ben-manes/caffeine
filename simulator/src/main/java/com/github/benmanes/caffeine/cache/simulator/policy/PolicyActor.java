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

import com.github.benmanes.caffeine.cache.simulator.Simulator;
import com.github.benmanes.caffeine.cache.simulator.Simulator.Stats;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

/**
 * An actor that proxies to the page replacement policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class PolicyActor extends AbstractBehavior<PolicyActor.Command> {
  private final ActorRef<Simulator.Command> simulator;
  private final Policy policy;

  public PolicyActor(ActorContext<Command> context,
      ActorRef<Simulator.Command> simulator, Policy policy) {
    super(context);
    this.policy = requireNonNull(policy);
    this.simulator = requireNonNull(simulator);
  }

  public static Behavior<Command> create(ActorRef<Simulator.Command> simulator, Policy policy) {
    return Behaviors.setup(context -> new PolicyActor(context, simulator, policy));
  }

  @Override
  public Receive<Command> createReceive() {
    return newReceiveBuilder()
        .onMessage(AccessEvents.class, msg -> process(msg.events))
        .onMessage(Finished.class, msg -> finish())
        .build();
  }

  private Behavior<Command> process(List<AccessEvent> events) {
    policy.stats().stopwatch().start();
    for (AccessEvent event : events) {
      long priorMisses = policy.stats().missCount();
      long priorHits = policy.stats().hitCount();
      policy.record(event);

      if (policy.stats().hitCount() > priorHits) {
        policy.stats().recordHitPenalty(event.hitPenalty());
      } else if (policy.stats().missCount() > priorMisses) {
        policy.stats().recordMissPenalty(event.missPenalty());
      }
    }
    policy.stats().stopwatch().stop();
    return this;
  }

  private Behavior<Command> finish() {
    policy.finished();
    simulator.tell(new Stats(policy.stats()));
    return Behaviors.stopped();
  }

  /** A message sent to the policy. */
  @SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
  public abstract static class Command {
    private Command() {}
  }
  public static final class AccessEvents extends Command {
    public final List<AccessEvent> events;
    public AccessEvents(List<AccessEvent> events) {
      this.events = requireNonNull(events);
    }
  }
  public static final class Finished extends Command {}
}
