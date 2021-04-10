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

import static com.github.benmanes.caffeine.cache.simulator.Simulator.Message.ERROR;
import static com.github.benmanes.caffeine.cache.simulator.Simulator.Message.FINISH;
import static java.util.Objects.requireNonNull;

import java.util.List;

import akka.actor.AbstractActor;
import akka.dispatch.BoundedMessageQueueSemantics;
import akka.dispatch.RequiresMessageQueue;

/**
 * An actor that proxies to the page replacement policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class PolicyActor extends AbstractActor
    implements RequiresMessageQueue<BoundedMessageQueueSemantics> {
  private final Policy policy;

  public PolicyActor(Policy policy) {
    this.policy = requireNonNull(policy);
  }

  @Override
  public Receive createReceive() {
    return receiveBuilder()
        .matchEquals(FINISH, msg -> finish())
        .matchUnchecked(List.class, () -> true, this::process)
        .build();
  }

  private void process(List<AccessEvent> events) {
    try {
      policy.stats().stopwatch().start();
      for (AccessEvent event : events) {
        long priorHits = policy.stats().hitCount();
        long priorMisses = policy.stats().missCount();

        policy.record(event);

        if (policy.stats().hitCount() > priorHits) {
          policy.stats().recordHitPenalty(event.hitPenalty());
        } else if (policy.stats().missCount() > priorMisses) {
          policy.stats().recordMissPenalty(event.missPenalty());
        }
      }
    } catch (Exception e) {
      sender().tell(ERROR, self());
      context().system().stop(self());
      context().system().log().error(e, "");
    } finally {
      policy.stats().stopwatch().stop();
    }
  }

  private void finish() {
    try {
      policy.finished();
      sender().tell(policy.stats(), self());
    } catch (Exception e) {
      sender().tell(ERROR, self());
      context().system().stop(self());
      context().system().log().error(e, "");
    }
  }
}
