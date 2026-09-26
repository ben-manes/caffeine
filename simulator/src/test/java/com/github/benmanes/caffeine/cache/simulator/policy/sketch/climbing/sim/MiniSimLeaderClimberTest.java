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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.sim;

import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation.Type.INCREASE_WINDOW;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation.adaptBy;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.QueueType.WINDOW;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.sim.MiniSimClimber.isSampled;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.sim.MiniSimClimber.targets;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber;
import com.google.errorprone.annotations.Var;
import com.typesafe.config.ConfigFactory;

/** Tests the targets, the evidence window, and the leader rule of the miniature leader. */
final class MiniSimLeaderClimberTest {

  @Test
  void simulatesTheInitialAndConfiguredWindows() {
    var targets = targets(1_000, 100, 0.99, 0.80, List.of(20, 40, 60, 80));

    assertThat(targets.stream().map(target -> target.full().maximumWindow()).toList())
        .containsExactly(10L, 200L, 400L, 600L, 800L).inOrder();
  }

  @Test
  void raisesTheMiniatureToItsMinimumSize() {
    assertThat(MiniSimLeaderClimber.samplingRate(8_192, 0)).isEqualTo(81);
    assertThat(MiniSimLeaderClimber.samplingRate(8_192, 400)).isEqualTo(20);
    assertThat(MiniSimLeaderClimber.samplingRate(8_192, 20_000)).isEqualTo(1);
    assertThat(MiniSimLeaderClimber.samplingRate(4_000_000, 400)).isEqualTo(1_000);
    assertThat(MiniSimLeaderClimber.samplingRate(4_000_000, 20_000)).isEqualTo(200);
  }

  @Test
  void tracesEachTargetsMissesPerSample() {
    var out = new ByteArrayOutputStream();
    var config = ConfigFactory.parseString("""
        maximum-size = 1000
        hill-climber-window-tiny-lfu.minisim-leader {
          percent-windows = [20, 40, 60, 80]
          miniature-size = 1000
          trace = true
        }
        """).withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
    var climber = new MiniSimLeaderClimber(0.99, config, new PrintStream(out, true, UTF_8));
    for (long key = 0; key < 500; key++) {
      climber.onMiss(key, /* isFull= */ false);
    }
    for (long i = 0; i < 7_500; i++) {
      climber.onHit(i % 500, WINDOW, /* isFull= */ false);
    }

    // The 500th insertion makes the cache half full, so the first sample closes at the next
    // multiple of 4,000 requests; it holds every first reference and the next sample none
    assertThat(out.toString(UTF_8).lines().toList()).containsExactly(
        "ghost s=0 win=10 n=4000 misses=10:500,200:500,400:500,600:500,800:500",
        "ghost s=1 win=10 n=4000 misses=10:0,200:0,400:0,600:0,800:0").inOrder();
  }

  @Test
  void excludesWarmupEvidence() {
    var climber = climber();
    replayPairs(climber, 20_000, /* isFull= */ false);
    replayUnsampled(climber, 1_000);

    // The pairs separated the miniatures while the cache filled, which does not count, and no
    // full-cache request reached them, so the initial window keeps the lead
    assertThat(climber.adapt(10, 198, 792, /* isFull= */ true)).isEqualTo(adaptBy(0));
  }

  @Test
  void followsTheMiniatureWithFewerMisses() {
    var climber = climber();
    replayPairs(climber, 20_000, /* isFull= */ true);

    // A pair's second reference outlives the initial window but not a larger one, so a larger
    // window leads
    assertThat(climber.adapt(10, 198, 792, /* isFull= */ true).type())
        .isEqualTo(INCREASE_WINDOW);
  }

  private static MiniSimLeaderClimber climber() {
    var config = ConfigFactory.parseString("""
        maximum-size = 1000
        hill-climber-window-tiny-lfu.minisim-leader {
          percent-windows = [20, 40, 60, 80]
          miniature-size = 100
        }
        """).withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
    return new MiniSimLeaderClimber(0.99, config);
  }

  /** Replays keys that are each requested twice, about a hundred requests apart. */
  private static void replayPairs(HillClimber climber, int count, boolean isFull) {
    for (long key = 0; key < count; key++) {
      climber.onMiss(key, isFull);
      if (key >= 50) {
        climber.onMiss(key - 50, isFull);
      }
    }
  }

  /** Replays full-cache requests that all hash outside the sampled bucket. */
  private static void replayUnsampled(HillClimber climber, int count) {
    @Var int replayed = 0;
    for (long key = 1_000_000; replayed < count; key++) {
      if (!isSampled(key, 10)) {
        climber.onMiss(key, /* isFull= */ true);
        replayed++;
      }
    }
  }
}
