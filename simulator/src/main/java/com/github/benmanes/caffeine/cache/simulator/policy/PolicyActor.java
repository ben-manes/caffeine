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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.google.common.collect.ImmutableList;

/**
 * An actor that proxies to the page replacement policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class PolicyActor {
  private final CompletableFuture<Void> completed;
  private final Semaphore semaphore;
  private final Policy policy;
  private final Thread parent;

  private CompletableFuture<Void> future;

  /**
   * Creates an actor that executes the policy actions asynchronously over a buffered channel.
   *
   * @param parent the supervisor to interrupt if the policy fails
   * @param policy the cache policy being simulated
   * @param settings the simulation settings
   */
  public PolicyActor(Thread parent, Policy policy, BasicSettings settings) {
    this.semaphore = new Semaphore(settings.actor().mailboxSize());
    this.future = CompletableFuture.completedFuture(null);
    this.completed = new CompletableFuture<>();
    this.policy = requireNonNull(policy);
    this.parent = requireNonNull(parent);
  }

  /** Sends the access events for async processing and blocks until accepted into the mailbox. */
  public void send(ImmutableList<AccessEvent> events) {
    submit(new Execute(events));
  }

  /** Sends a shutdown signal after the pending messages are completed. */
  public void finish() {
    submit(new Finish());
  }

  /** Return the future that signals the policy's completion. */
  public CompletableFuture<Void> completed() {
    return completed;
  }

  /** Submits the command to the mailbox and blocks until accepted. */
  private void submit(Command command) {
    try {
      semaphore.acquire();
      future = future.thenRunAsync(command);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Returns the cache efficiency statistics. */
  public PolicyStats stats() {
    return policy.stats();
  }

  /** A command to process the access events. */
  private final class Execute extends Command {
    final List<AccessEvent> events;

    Execute(List<AccessEvent> events) {
      this.events = requireNonNull(events);
    }
    @Override public void execute() {
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
    }
  }

  /** A command to shutdown the policy and finalize the statistics. */
  private final class Finish extends Command {
    @Override public void execute() {
      policy.finished();
      completed.complete(null);
    }
  }

  private abstract class Command implements Runnable {
    @Override public final void run() {
      var name = Thread.currentThread().getName();
      Thread.currentThread().setName(policy.getClass().getSimpleName());
      try {
        execute();
      } catch (Throwable t) {
        completed.completeExceptionally(t);
        parent.interrupt();
        throw t;
      } finally {
        semaphore.release();
        Thread.currentThread().setName(name);
      }
    }
    protected abstract void execute();
  }
}
