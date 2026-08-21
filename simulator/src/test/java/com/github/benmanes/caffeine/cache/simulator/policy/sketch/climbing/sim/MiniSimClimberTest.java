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

import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation.adaptBy;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.sim.MiniSimClimber.adaptToward;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.sim.MiniSimClimber.initialSegments;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.sim.MiniSimClimber.scaleTargetSegments;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.sim.MiniSimClimber.targetSegments;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.sim.MiniSimClimber.targets;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.sim.MiniSimClimber.SegmentSizes;
import com.google.common.hash.Hashing;
import com.google.errorprone.annotations.Var;
import com.typesafe.config.ConfigFactory;

/** Tests the reachable geometry, actuation, and epoch semantics of the miniature simulations. */
final class MiniSimClimberTest {

  @Test
  void epochClockStartsAtFill() {
    var climber = climber();
    for (long key = 0; key < 500; key++) {
      climber.onMiss(key, /* isFull= */ false);
    }
    climber.onMiss(500, /* isFull= */ true);

    // The 500 unfilled requests did not advance the epoch clock, so a full period must still
    // elapse before the first adaptation.
    assertThat(climber.adapt(51, 198, 751, /* isFull= */ true)).isEqualTo(adaptBy(0));
  }

  @Test
  void abstainsWithoutDiscriminatingEvidence() {
    var climber = climber();
    replayUnsampled(climber, 101);

    // No sampled request reached the miniatures, so every arm ties and the climber holds
    // rather than electing the first target.
    assertThat(climber.adapt(51, 198, 751, /* isFull= */ true)).isEqualTo(adaptBy(0));
  }

  @Test
  void firstEpochExcludesWarmupEvidence() {
    var climber = climber();
    replayLoop(climber, 10, /* isFull= */ false);
    replayUnsampled(climber, 101);

    // The warmup loop separated the arms, but those misses precede the fill, so the first
    // epoch judges only its own uniform, unsampled evidence and holds.
    assertThat(climber.adapt(51, 198, 751, /* isFull= */ true)).isEqualTo(adaptBy(0));
  }

  @Test
  void adaptsOnDiscriminatingEvidence() {
    var climber = climber();
    replayLoop(climber, 3, /* isFull= */ false);
    replayLoop(climber, 2, /* isFull= */ true);

    assertThat(climber.adapt(51, 198, 751, /* isFull= */ true)).isNotEqualTo(adaptBy(0));
  }

  private static MiniSimClimber climber() {
    var config = ConfigFactory.parseString("""
        maximum-size = 1000
        hill-climber-window-tiny-lfu.minisim.period = 100
        """).withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
    return new MiniSimClimber(0.99, config);
  }

  /** Replays a cyclic scan whose sampled subset exceeds the miniature capacity. */
  private static void replayLoop(HillClimber climber, int passes, boolean isFull) {
    for (int pass = 0; pass < passes; pass++) {
      for (long key = 0; key < 1_200; key++) {
        climber.onMiss(key, isFull);
      }
    }
  }

  /** Replays full-cache requests that all hash outside the sampled bucket. */
  private static void replayUnsampled(HillClimber climber, int count) {
    @Var int replayed = 0;
    for (long key = 1_000_000; replayed < count; key++) {
      if (!isSampled(key)) {
        climber.onMiss(key, /* isFull= */ true);
        replayed++;
      }
    }
  }

  /** Mirrors the climber's sampling hash at the test configuration's sampling rate of 10. */
  private static boolean isSampled(long key) {
    return Math.floorMod(Hashing.murmur3_32_fixed(0x7f3a2142).hashLong(key).asInt(), 10) < 1;
  }

  @Test
  void preservesProbationAcrossTargets() {
    var initial = initialSegments(1_000, 0.99, 0.80);
    var target50 = targetSegments(1_000, 50, initial.maximumProbation());
    var target80 = targetSegments(1_000, 80, initial.maximumProbation());

    assertThat(initial).isEqualTo(new SegmentSizes(10, 792, 198));
    assertThat(target50).isEqualTo(new SegmentSizes(500, 302, 198));
    assertThat(target80).isEqualTo(new SegmentSizes(800, 2, 198));
    assertThat(scaleTargetSegments(1_000, 100, target80, 20))
        .isEqualTo(new SegmentSizes(80, 0, 20));
  }

  @Test
  void clampsTargetsToTheReachableRail() {
    var initial = initialSegments(64, 0.99, 0.80);

    assertThat(initial).isEqualTo(new SegmentSizes(1, 50, 13));
    assertThat(targetSegments(64, 80, initial.maximumProbation()))
        .isEqualTo(new SegmentSizes(51, 0, 13));
  }

  @Test
  void usesTheHostProtectedFraction() {
    var targets = targets(608, 304, 0.99, 0.19);
    var terminal = targets.getLast();

    assertThat(terminal.full()).isEqualTo(new SegmentSizes(121, 0, 487));
    assertThat(terminal.miniature()).isEqualTo(new SegmentSizes(60, 1, 243));
    assertThat(targets.stream().map(target -> target.full().maximumProbation()).distinct().toList())
        .containsExactly(487L);
    assertThat(targets.stream()
        .map(target -> target.miniature().maximumProbation()).distinct().toList())
        .containsExactly(243L);
  }

  @Test
  void deduplicatesScaledAliases() {
    var targets = targets(201, 100, 0.99, 0.80);
    var miniatureSegments = targets.stream().map(MiniSimClimber.Target::miniature).toList();
    var zeroWindow = targets.stream()
        .filter(target -> target.miniature().maximumWindow() == 0)
        .findFirst()
        .orElseThrow();

    assertThat(targets).hasSize(80);
    assertThat(miniatureSegments).containsNoDuplicates();
    assertThat(targets.getFirst().full()).isEqualTo(new SegmentSizes(3, 158, 40));
    assertThat(targets.getFirst().miniature()).isEqualTo(new SegmentSizes(1, 79, 20));
    assertThat(zeroWindow.full().maximumWindow()).isEqualTo(0);
  }

  @Test
  void incumbentAliasHoldsAtTheLiveCoordinate() {
    var targets = targets(333, 111, 0.99, 0.80);
    var incumbent = targets.getFirst();

    assertThat(incumbent.full()).isEqualTo(new SegmentSizes(4, 263, 66));
    assertThat(incumbent.miniature()).isEqualTo(new SegmentSizes(1, 88, 22));
    assertThat(adaptToward(incumbent.full().maximumWindow(), 4)).isEqualTo(adaptBy(0));
  }

  @Test
  void adaptsFromTheLiveIntegerCoordinate() {
    var initial = initialSegments(333, 0.99, 0.80);
    var target50 = targetSegments(333, 50, initial.maximumProbation());
    var target80 = targetSegments(333, 80, initial.maximumProbation());

    assertThat(initial).isEqualTo(new SegmentSizes(4, 263, 66));
    assertThat(target50).isEqualTo(new SegmentSizes(166, 101, 66));
    assertThat(target80).isEqualTo(new SegmentSizes(266, 1, 66));
    assertThat(adaptToward(166, 4)).isEqualTo(adaptBy(162));
    assertThat(adaptToward(266, 166)).isEqualTo(adaptBy(100));
    assertThat(adaptToward(166, 266)).isEqualTo(adaptBy(-100));
    assertThat(adaptToward(166, 166)).isEqualTo(adaptBy(0));
    assertThat(adaptToward(166, 165)).isEqualTo(adaptBy(1));
    assertThat(adaptToward(166, 167)).isEqualTo(adaptBy(-1));
  }

  @Test
  void rejectsFractionalLiveCoordinate() {
    assertThrows(IllegalStateException.class, () -> adaptToward(10, 9.5));
  }
}
