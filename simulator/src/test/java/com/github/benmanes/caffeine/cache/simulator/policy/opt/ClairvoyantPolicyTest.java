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
package com.github.benmanes.caffeine.cache.simulator.policy.opt;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.simulator.parser.ClairvoyantTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader.KeyOnlyTraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.MustBeClosed;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * The next-access times are precomputed by the {@link ClairvoyantTraceReader} to a temporary file
 * so only the resident set is held in memory. These checks confirm the replay is exactly
 * {@literal Bélády's} optimal (identical to the prior in-memory implementation) and that the
 * temporary file is cleaned up.
 */
final class ClairvoyantPolicyTest {

  @Test
  void belady_evictsTheFarthestFutureUse() {
    // A B C A B @ size 2: C is never used again, so it is the eviction victim, leaving A and B to
    // hit -> 2 hits, 3 (compulsory) misses, 1 eviction.
    var stats = run(2, /* penalties= */ false, 1, 2, 3, 1, 2);
    assertThat(stats.hitCount()).isEqualTo(2);
    assertThat(stats.missCount()).isEqualTo(3);
    assertThat(stats.evictionCount()).isEqualTo(1);
  }

  @Test
  void matchesInMemoryReference() {
    var random = new Random(42);
    var keys = new long[2000];
    for (int i = 0; i < keys.length; i++) {
      keys[i] = random.nextInt(50);
    }
    var stats = run(8, /* penalties= */ false, keys);
    // Bit-for-bit regression oracle captured from the prior in-memory implementation.
    assertThat(stats.hitCount()).isEqualTo(905);
    assertThat(stats.missCount()).isEqualTo(1095);
    assertThat(stats.evictionCount()).isEqualTo(1087);
  }

  @Test
  void penaltyAware_replaysWiderRecord() {
    var stats = run(4, /* penalties= */ true, 1, 2, 3, 1, 2, 3, 4, 1);
    assertThat(stats.hitCount()).isEqualTo(4);
    assertThat(stats.missCount()).isEqualTo(4);
    assertThat(stats.evictionCount()).isEqualTo(0);
    assertThat(stats.hitPenalty()).isGreaterThan(0.0);
    assertThat(stats.missPenalty()).isGreaterThan(0.0);
  }

  @Test
  void mixingPenaltyAwareness_isRejected() {
    assertThrows(IllegalStateException.class, () -> {
      try (var reader = new ClairvoyantTraceReader(mixedTrace(), 0, Long.MAX_VALUE)) {
        reader.characteristics();
      }
    });
  }

  @Test
  @SuppressWarnings("CheckReturnValue")
  void oracleFileIsDeleted() throws IOException {
    int before = oracleFiles();
    run(2, /* penalties= */ false, 1, 2, 3, 1, 2);
    assertThat(oracleFiles()).isAtMost(before);
  }

  private static PolicyStats run(int maximumSize, boolean penalties, long... keys) {
    try (var reader = new ClairvoyantTraceReader(trace(penalties, keys), 0, Long.MAX_VALUE)) {
      return reader.scoped(() -> {
        var policy = new ClairvoyantPolicy(config(maximumSize));
        var stats = policy.stats();
        try (var events = reader.events()) {
          events.forEach(event -> {
            // Mirror the PolicyActor: it attributes the penalty from the hit/miss it observes.
            long priorHits = stats.hitCount();
            long priorMisses = stats.missCount();
            policy.record(event);
            if (stats.hitCount() > priorHits) {
              stats.recordHitPenalty(event.hitPenalty());
            } else if (stats.missCount() > priorMisses) {
              stats.recordMissPenalty(event.missPenalty());
            }
          });
        }
        policy.finished();
        return stats;
      });
    }
  }

  private static TraceReader trace(boolean penalties, long[] keys) {
    if (!penalties) {
      return (KeyOnlyTraceReader) () -> Arrays.stream(keys);
    }
    return new TraceReader() {
      @Override public Set<Characteristic> characteristics() {
        return Set.of();
      }
      @Override @MustBeClosed public Stream<AccessEvent> events() {
        return Arrays.stream(keys).mapToObj(key ->
            AccessEvent.forKeyAndPenalties(key, 1.0 + key, 10.0 + key));
      }
    };
  }

  private static TraceReader mixedTrace() {
    return new TraceReader() {
      @Override public Set<Characteristic> characteristics() {
        return Set.of();
      }
      @Override @MustBeClosed public Stream<AccessEvent> events() {
        return Stream.of(AccessEvent.forKey(1), AccessEvent.forKeyAndPenalties(2, 1.0, 2.0));
      }
    };
  }

  private static int oracleFiles() throws IOException {
    var directory = Path.of(System.getProperty("java.io.tmpdir"));
    try (var stream = Files.newDirectoryStream(directory, "clairvoyant*.oracle")) {
      return Iterables.size(stream);
    }
  }

  private static Config config(long maximumSize) {
    return ConfigFactory.parseMap(Map.of("maximum-size", maximumSize))
        .withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
  }
}
