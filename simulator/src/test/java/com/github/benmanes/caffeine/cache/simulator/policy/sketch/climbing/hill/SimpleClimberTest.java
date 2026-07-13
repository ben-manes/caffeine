/*
 * Copyright 2026 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.hill;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.QueueType;
import com.google.errorprone.annotations.Var;
import com.typesafe.config.ConfigFactory;

/**
 * SimpleClimber mirrors the production BoundedLocalCache hill climber: small caches grow the window
 * first, and the climber never permanently stops adapting so it can still react to a later workload
 * shift.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class SimpleClimberTest {

  @Test
  void smallCacheGrowsWindowFirst() {
    assertThat(firstStep(climber(100)).type()).isEqualTo(Adaptation.Type.INCREASE_WINDOW);
  }

  @Test
  void largeCacheShrinksWindowFirst() {
    assertThat(firstStep(climber(1000)).type()).isEqualTo(Adaptation.Type.DECREASE_WINDOW);
  }

  @Test
  @SuppressWarnings("CheckReturnValue")
  void doesNotPermanentlyFreezeOnLargeCaches() {
    var climber = climber(1000, "percent-sample = 0.01");
    // stable hits well past the ~434-period point where the old code froze the sampler
    for (int i = 0; i < 20_000; i++) {
      climber.onHit(i, QueueType.WINDOW, /* isFull= */ true);
      climber.adapt(/* windowSize= */ 5, 0, 0, /* isFull= */ true);
    }
    // a workload shift must still be adapted to; a frozen sampler would hold forever
    @Var boolean adapted = false;
    for (int i = 0; i < 1_000; i++) {
      climber.onMiss(i, /* isFull= */ true);
      if (climber.adapt(5, 0, 0, /* isFull= */ true).type() != Adaptation.Type.HOLD) {
        adapted = true;
        break;
      }
    }
    assertThat(adapted).isTrue();
  }

  /** Feeds hit samples until the first non-hold adaptation and returns it. */
  private static Adaptation firstStep(HillClimber climber) {
    for (int i = 0; ; i++) {
      climber.onHit(i, QueueType.WINDOW, /* isFull= */ true);
      var adaptation = climber.adapt(/* windowSize= */ 5, 0, 0, /* isFull= */ true);
      if (adaptation.type() != Adaptation.Type.HOLD) {
        return adaptation;
      }
    }
  }

  private static SimpleClimber climber(int maximumSize, String... simpleOverrides) {
    var overrides = new StringBuilder(200)
        .append("caffeine.simulator.maximum-size = ").append(maximumSize).append('\n');
    for (var override : simpleOverrides) {
      overrides.append("caffeine.simulator.hill-climber-window-tiny-lfu.simple.")
          .append(override).append('\n');
    }
    var config = ConfigFactory.parseString(overrides.toString())
        .withFallback(ConfigFactory.load())
        .getConfig("caffeine.simulator");
    return new SimpleClimber(config);
  }
}
