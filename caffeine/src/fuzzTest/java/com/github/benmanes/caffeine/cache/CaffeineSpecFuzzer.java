/*
 * Copyright 2024 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.cache.BoundedLocalCache.MAXIMUM_CAPACITY;
import static com.google.common.truth.Truth.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.DictionaryEntries;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import com.github.benmanes.caffeine.cache.Caffeine.Strength;
import com.google.common.collect.ImmutableMap;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings({"DefaultAnnotationParam", "NewClassNamingConvention",
    "PMD.MissingStaticMethodInNonInstantiatableClass"})
final class CaffeineSpecFuzzer {

  // These tests require the environment variable JAZZER_FUZZ=1 to try new input arguments

  private CaffeineSpecFuzzer() {}

  @Nested
  static final class ParseTest {

    @DictionaryEntries({"=", " ", "+52", "+5_2", "+_52", ",", "-52", "-5_2", "-_52", "52_", "5_2",
        "_52", "expireAfterAccess", "expireAfterWrite", "initialCapacity", "maximumSize",
        "maximumWeight", "recordStats", "refreshAfterWrite", "softValues", "weakKeys",
        "weakValues"})
    @FuzzTest(maxDuration = "5m")
    void parse(@NotNull String specification) {
      try {
        assertThat(CaffeineSpec.parse(specification)).isNotNull();
      } catch (IllegalArgumentException expected) { /* ignored */ }
    }
  }

  @Nested
  static final class StructuredTest {

    /** Builds a spec string from structured inputs and verifies the cache honors each setting. */
    @FuzzTest(maxDuration = "5m")
    void structured(FuzzedDataProvider data) {
      var options = new Options(data);
      try {
        var spec = CaffeineSpec.parse(options.toSpecification());
        var builder = Caffeine.from(spec);
        if (options.maximumWeight != null) {
          builder.weigher((key, value) -> 1);
        }
        var cache = builder.build(key -> key);
        var policy = cache.policy();

        checkInitialCapacity(options, spec);
        checkRecordStats(options, spec, policy);
        checkMaximumSize(options, spec, policy);
        checkKeyStrength(options, spec, builder);
        checkMaximumWeight(options, spec, policy);
        checkValueStrength(options, spec, builder);
        checkExpireAfterWrite(options, spec, policy);
        checkExpireAfterAccess(options, spec, policy);
        checkRefreshAfterWrite(options, spec, policy);
      } catch (IllegalArgumentException expected) { /* invalid combination */ }
    }

    private static void checkKeyStrength(
        Options options, CaffeineSpec spec, Caffeine<?, ?> builder) {
      if (options.keyStrength == null) {
        assertThat(spec.keyStrength).isNull();
        assertThat(builder.keyStrength).isNull();
      } else {
        assertThat(spec.keyStrength).isNotNull();
        assertThat(spec.keyStrength).isEqualTo(options.keyStrength);
        assertThat(builder.keyStrength).isEqualTo(options.keyStrength);
      }
    }

    private static void checkValueStrength(
        Options options, CaffeineSpec spec, Caffeine<?, ?> builder) {
      if (options.valueStrength == null) {
        assertThat(spec.valueStrength).isNull();
        assertThat(builder.valueStrength).isNull();
      } else {
        assertThat(spec.valueStrength).isNotNull();
        assertThat(spec.valueStrength).isEqualTo(options.valueStrength);
        assertThat(builder.valueStrength).isEqualTo(options.valueStrength);
      }
    }

    private static void checkInitialCapacity(Options options, CaffeineSpec spec) {
      if (options.initialCapacity == null) {
        assertThat(spec.initialCapacity).isNull();
      } else {
        assertThat(spec.initialCapacity).isNotNull();
        assertThat(spec.initialCapacity).isAtLeast(0);
        assertThat(spec.initialCapacity).isEqualTo(options.initialCapacity);
      }
    }

    private static void checkRecordStats(Options options, CaffeineSpec spec, Policy<?, ?> policy) {
      if (options.recordStats) {
        assertThat(spec.recordStats).isTrue();
        assertThat(policy.isRecordingStats()).isTrue();
      } else {
        assertThat(spec.recordStats).isFalse();
        assertThat(policy.isRecordingStats()).isFalse();
      }
    }

    private static void checkMaximumSize(Options options, CaffeineSpec spec, Policy<?, ?> policy) {
      if (options.maximumSize == null) {
        assertThat(spec.maximumSize).isNull();
        if (options.maximumWeight == null) {
          assertThat(policy.eviction()).isEmpty();
        }
      } else {
        assertThat(spec.maximumSize).isNotNull();
        assertThat(spec.maximumSize).isAtLeast(0);
        assertThat(spec.maximumSize).isEqualTo(options.maximumSize);

        assertThat(policy.eviction()).isPresent();
        assertThat(policy.eviction().orElseThrow().isWeighted()).isFalse();
        if (options.maximumSize > MAXIMUM_CAPACITY) {
          assertThat(policy.eviction().orElseThrow().getMaximum()).isEqualTo(MAXIMUM_CAPACITY);
        } else {
          assertThat(policy.eviction().orElseThrow().getMaximum()).isEqualTo(options.maximumSize);
        }
      }
    }

    private static void checkMaximumWeight(
        Options options, CaffeineSpec spec, Policy<?, ?> policy) {
      if (options.maximumWeight == null) {
        assertThat(spec.maximumWeight).isNull();
        if (options.maximumSize == null) {
          assertThat(policy.eviction()).isEmpty();
        }
      } else {
        assertThat(spec.maximumWeight).isNotNull();
        assertThat(spec.maximumWeight).isAtLeast(0);
        assertThat(spec.maximumWeight).isEqualTo(options.maximumWeight);

        assertThat(policy.eviction()).isPresent();
        assertThat(policy.eviction().orElseThrow().isWeighted()).isTrue();
        if (options.maximumWeight > MAXIMUM_CAPACITY) {
          assertThat(policy.eviction().orElseThrow().getMaximum()).isEqualTo(MAXIMUM_CAPACITY);
        } else {
          assertThat(policy.eviction().orElseThrow().getMaximum()).isEqualTo(options.maximumWeight);
        }
      }
    }

    private static void checkExpireAfterAccess(
        Options options, CaffeineSpec spec, Policy<?, ?> policy) {
      if (options.expireAfterAccess == null) {
        assertThat(spec.expireAfterAccess).isNull();
        assertThat(policy.expireAfterAccess()).isEmpty();
      } else {
        assertThat(spec.expireAfterAccess).isNotNull();
        assertThat(spec.expireAfterAccess).isEqualTo(options.expireAfterAccess.toDuration());
        assertThat(policy.expireAfterAccess()).isPresent();
        assertThat(policy.expireAfterAccess().orElseThrow().getExpiresAfter())
            .isEqualTo(spec.expireAfterAccess);
      }
    }

    private static void checkExpireAfterWrite(
        Options options, CaffeineSpec spec, Policy<?, ?> policy) {
      if (options.expireAfterWrite == null) {
        assertThat(spec.expireAfterWrite).isNull();
        assertThat(policy.expireAfterWrite()).isEmpty();
      } else {
        assertThat(spec.expireAfterWrite).isNotNull();
        assertThat(spec.expireAfterWrite).isEqualTo(options.expireAfterWrite.toDuration());
        assertThat(policy.expireAfterWrite()).isPresent();
        assertThat(policy.expireAfterWrite().orElseThrow().getExpiresAfter())
            .isEqualTo(spec.expireAfterWrite);
      }
    }

    private static void checkRefreshAfterWrite(
        Options options, CaffeineSpec spec, Policy<?, ?> policy) {
      if (options.refreshAfterWrite == null) {
        assertThat(spec.refreshAfterWrite).isNull();
        assertThat(policy.refreshAfterWrite()).isEmpty();
      } else {
        assertThat(spec.refreshAfterWrite).isNotNull();
        assertThat(spec.refreshAfterWrite).isEqualTo(options.refreshAfterWrite.toDuration());
        assertThat(policy.refreshAfterWrite()).isPresent();
        assertThat(policy.refreshAfterWrite().orElseThrow().getRefreshesAfter())
            .isEqualTo(spec.refreshAfterWrite);
      }
    }

    private static final class Options {
      final Random random;
      final boolean recordStats;
      final @Nullable Long maximumSize;
      final @Nullable Long maximumWeight;
      final @Nullable Strength keyStrength;
      final @Nullable Strength valueStrength;
      final @Nullable Integer initialCapacity;
      final @Nullable TimedValue expireAfterWrite;
      final @Nullable TimedValue expireAfterAccess;
      final @Nullable TimedValue refreshAfterWrite;

      Options(FuzzedDataProvider data) {
        recordStats = data.consumeBoolean();
        random = new Random(data.consumeLong());
        keyStrength = data.consumeBoolean() ? Strength.WEAK : null;
        valueStrength = data.consumeBoolean()
            ? (data.consumeBoolean() ? Strength.WEAK : Strength.SOFT)
            : null;
        initialCapacity = data.consumeBoolean()
            ? data.consumeInt(Integer.MIN_VALUE, 1_000_000)
            : null;
        maximumSize = data.consumeBoolean() ? data.consumeLong() : null;
        maximumWeight = data.consumeBoolean() ? data.consumeLong() : null;
        expireAfterWrite = data.consumeBoolean() ? new TimedValue(data) : null;
        expireAfterAccess = data.consumeBoolean() ? new TimedValue(data) : null;
        refreshAfterWrite = data.consumeBoolean() ? new TimedValue(data) : null;
      }

      String toSpecification() {
        var options = new ArrayList<String>();
        if (recordStats) {
          options.add("recordStats");
        }
        if (initialCapacity != null) {
          options.add("initialCapacity=" + initialCapacity);
        }
        if (keyStrength == Strength.WEAK) {
          options.add("weakKeys");
        }
        if (valueStrength == Strength.WEAK) {
          options.add("weakValues");
        } else if (valueStrength == Strength.SOFT) {
          options.add("softValues");
        }
        if (maximumSize != null) {
          options.add("maximumSize=" + maximumSize);
        }
        if (maximumWeight != null) {
          options.add("maximumWeight=" + maximumWeight);
        }
        if (expireAfterWrite != null) {
          options.add("expireAfterWrite=" + expireAfterWrite);
        }
        if (expireAfterAccess != null) {
          options.add("expireAfterAccess=" + expireAfterAccess);
        }
        if (refreshAfterWrite != null) {
          options.add("refreshAfterWrite=" + refreshAfterWrite);
        }
        Collections.shuffle(options, random);
        return String.join(",", options);
      }
    }

    private static final class TimedValue {
      static final ImmutableMap<TimeUnit, String> UNITS = ImmutableMap.of(
          TimeUnit.DAYS, "d", TimeUnit.HOURS, "h", TimeUnit.MINUTES, "m", TimeUnit.SECONDS, "s");

      final long duration;
      final TimeUnit unit;

      TimedValue(FuzzedDataProvider data) {
        duration = data.consumeLong();
        unit = data.pickValue(UNITS.keySet());
      }
      Duration toDuration() {
        return Duration.ofNanos(unit.toNanos(duration));
      }
      @Override public String toString() {
        return duration + UNITS.get(unit);
      }
    }
  }
}
