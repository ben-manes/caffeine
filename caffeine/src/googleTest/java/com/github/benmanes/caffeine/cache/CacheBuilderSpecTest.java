/*
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.cache.Caffeine.UNSET_INT;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Caffeine.Strength;
import com.google.common.testing.EqualsTester;

/**
 * Tests CacheBuilderSpec. TODO(user): tests of a few invalid input conditions, boundary
 * conditions.
 *
 * @author Adam Winer
 */
@SuppressWarnings("PreferJavaTimeOverload")
final class CacheBuilderSpecTest {

  @Test
  void parse_empty() {
    CaffeineSpec spec = parse("");
    assertThat(spec.initialCapacity).isEqualTo(UNSET_INT);
    assertThat(spec.maximumSize).isEqualTo(UNSET_INT);
    assertThat(spec.maximumWeight).isEqualTo(UNSET_INT);
    assertThat(spec.keyStrength).isNull();
    assertThat(spec.valueStrength).isNull();
    assertThat(spec.expireAfterAccess).isNull();
    assertThat(spec.expireAfterAccess).isNull();
    assertThat(spec.refreshAfterWrite).isNull();
    assertCacheBuilderEquivalence(Caffeine.newBuilder(), Caffeine.from(spec));
  }

  @Test
  void parse_initialCapacity() {
    CaffeineSpec spec = parse("initialCapacity=10");
    assertThat(spec.initialCapacity).isEqualTo(10);
    assertThat(spec.maximumSize).isEqualTo(UNSET_INT);
    assertThat(spec.maximumWeight).isEqualTo(UNSET_INT);
    assertThat(spec.keyStrength).isNull();
    assertThat(spec.valueStrength).isNull();
    assertThat(spec.expireAfterAccess).isNull();
    assertThat(spec.expireAfterAccess).isNull();
    assertThat(spec.refreshAfterWrite).isNull();
    assertCacheBuilderEquivalence(
        Caffeine.newBuilder().initialCapacity(10), Caffeine.from(spec));
  }

  @Test
  void parse_initialCapacityRepeated() {
    assertThrows(IllegalArgumentException.class,
        () -> parse("initialCapacity=10, initialCapacity=20"));
  }

  @Test
  void parse_maximumSize() {
    CaffeineSpec spec = parse("maximumSize=9000");
    assertThat(spec.initialCapacity).isEqualTo(UNSET_INT);
    assertThat(spec.maximumSize).isEqualTo(9000);
    assertThat(spec.keyStrength).isNull();
    assertThat(spec.valueStrength).isNull();
    assertThat(spec.expireAfterAccess).isNull();
    assertThat(spec.expireAfterAccess).isNull();
    assertThat(spec.refreshAfterWrite).isNull();
    assertCacheBuilderEquivalence(
        Caffeine.newBuilder().maximumSize(9000), Caffeine.from(spec));
  }

  @Test
  void parse_maximumSizeRepeated() {
    assertThrows(IllegalArgumentException.class, () -> parse("maximumSize=10, maximumSize=20"));
  }

  @Test
  void parse_maximumWeight() {
    CaffeineSpec spec = parse("maximumWeight=9000");
    assertThat(spec.initialCapacity).isEqualTo(UNSET_INT);
    assertThat(spec.maximumWeight).isEqualTo(9000);
    assertThat(spec.keyStrength).isNull();
    assertThat(spec.valueStrength).isNull();
    assertThat(spec.expireAfterAccess).isNull();
    assertThat(spec.expireAfterAccess).isNull();
    assertThat(spec.refreshAfterWrite).isNull();
    assertCacheBuilderEquivalence(
        Caffeine.newBuilder().maximumWeight(9000), Caffeine.from(spec));
  }

  @Test
  void parse_maximumWeightRepeated() {
    assertThrows(IllegalArgumentException.class, () -> parse("maximumWeight=10, maximumWeight=20"));
  }

  @Test
  void parse_maximumSizeAndMaximumWeight() {
    assertThrows(IllegalArgumentException.class, () -> parse("maximumSize=10, maximumWeight=20"));
  }

  @Test
  void parse_weakKeys() {
    CaffeineSpec spec = parse("weakKeys");
    assertThat(spec.initialCapacity).isEqualTo(UNSET_INT);
    assertThat(spec.maximumSize).isEqualTo(UNSET_INT);
    assertThat(spec.maximumWeight).isEqualTo(UNSET_INT);
    assertThat(spec.keyStrength).isEqualTo(Strength.WEAK);
    assertThat(spec.valueStrength).isNull();
    assertThat(spec.expireAfterWrite).isNull();
    assertThat(spec.expireAfterAccess).isNull();
    assertThat(spec.refreshAfterWrite).isNull();
    assertCacheBuilderEquivalence(
        Caffeine.newBuilder().weakKeys(), Caffeine.from(spec));
  }

  @Test
  void parse_weakKeysCannotHaveValue() {
    assertThrows(IllegalArgumentException.class, () -> parse("weakKeys=true"));
  }

  @Test
  void parse_repeatedKeyStrength() {
    assertThrows(IllegalArgumentException.class, () -> parse("weakKeys, weakKeys"));
  }

  @Test
  void parse_softValues() {
    CaffeineSpec spec = parse("softValues");
    assertThat(spec.initialCapacity).isEqualTo(UNSET_INT);
    assertThat(spec.maximumSize).isEqualTo(UNSET_INT);
    assertThat(spec.maximumWeight).isEqualTo(UNSET_INT);
    assertThat(spec.keyStrength).isNull();
    assertThat(spec.valueStrength).isEqualTo(Strength.SOFT);
    assertThat(spec.expireAfterWrite).isNull();
    assertThat(spec.expireAfterAccess).isNull();
    assertThat(spec.refreshAfterWrite).isNull();
    assertCacheBuilderEquivalence(
        Caffeine.newBuilder().softValues(), Caffeine.from(spec));
  }

  @Test
  void parse_softValuesCannotHaveValue() {
    assertThrows(IllegalArgumentException.class, () -> parse("softValues=true"));
  }

  @Test
  void parse_weakValues() {
    CaffeineSpec spec = parse("weakValues");
    assertThat(spec.initialCapacity).isEqualTo(UNSET_INT);
    assertThat(spec.maximumSize).isEqualTo(UNSET_INT);
    assertThat(spec.maximumWeight).isEqualTo(UNSET_INT);
    assertThat(spec.keyStrength).isNull();
    assertThat(spec.valueStrength).isEqualTo(Strength.WEAK);
    assertThat(spec.expireAfterWrite).isNull();
    assertThat(spec.expireAfterAccess).isNull();
    assertThat(spec.refreshAfterWrite).isNull();
    assertCacheBuilderEquivalence(
        Caffeine.newBuilder().weakValues(), Caffeine.from(spec));
  }

  @Test
  void parse_weakValuesCannotHaveValue() {
    assertThrows(IllegalArgumentException.class, () -> parse("weakValues=true"));
  }

  @Test
  void parse_repeatedValueStrength() {
    assertThrows(IllegalArgumentException.class, () -> parse("softValues, softValues"));

    assertThrows(IllegalArgumentException.class, () -> parse("softValues, weakValues"));

    assertThrows(IllegalArgumentException.class, () -> parse("weakValues, softValues"));

    assertThrows(IllegalArgumentException.class, () -> parse("weakValues, weakValues"));
  }

  @Test
  void parse_writeExpirationDays() {
    CaffeineSpec spec = parse("expireAfterWrite=10d");
    assertThat(spec.initialCapacity).isEqualTo(UNSET_INT);
    assertThat(spec.maximumSize).isEqualTo(UNSET_INT);
    assertThat(spec.maximumWeight).isEqualTo(UNSET_INT);
    assertThat(spec.keyStrength).isNull();
    assertThat(spec.valueStrength).isNull();
    assertThat(spec.expireAfterWrite).isEqualTo(Duration.ofDays(10));
    assertThat(spec.expireAfterAccess).isNull();
    assertThat(spec.refreshAfterWrite).isNull();
    assertCacheBuilderEquivalence(
        Caffeine.newBuilder().expireAfterWrite(10L, TimeUnit.DAYS), Caffeine.from(spec));
  }

  @Test
  void parse_writeExpirationHours() {
    CaffeineSpec spec = parse("expireAfterWrite=150h");
    assertThat(spec.expireAfterWrite).isEqualTo(Duration.ofHours(150));
    assertCacheBuilderEquivalence(
        Caffeine.newBuilder().expireAfterWrite(150L, TimeUnit.HOURS), Caffeine.from(spec));
  }

  @Test
  void parse_writeExpirationMinutes() {
    CaffeineSpec spec = parse("expireAfterWrite=10m");
    assertThat(spec.expireAfterWrite).isEqualTo(Duration.ofMinutes(10));
    assertCacheBuilderEquivalence(
        Caffeine.newBuilder().expireAfterWrite(10L, TimeUnit.MINUTES), Caffeine.from(spec));
  }

  @Test
  void parse_writeExpirationSeconds() {
    CaffeineSpec spec = parse("expireAfterWrite=10s");
    assertThat(spec.expireAfterWrite).isEqualTo(Duration.ofSeconds(10));
    assertCacheBuilderEquivalence(
        Caffeine.newBuilder().expireAfterWrite(10L, TimeUnit.SECONDS), Caffeine.from(spec));
  }

  @Test
  void parse_writeExpirationRepeated() {
    assertThrows(IllegalArgumentException.class,
        () -> parse("expireAfterWrite=10s,expireAfterWrite=10m"));
  }

  @Test
  void parse_accessExpirationDays() {
    CaffeineSpec spec = parse("expireAfterAccess=10d");
    assertThat(spec.initialCapacity).isEqualTo(UNSET_INT);
    assertThat(spec.maximumSize).isEqualTo(UNSET_INT);
    assertThat(spec.maximumWeight).isEqualTo(UNSET_INT);
    assertThat(spec.keyStrength).isNull();
    assertThat(spec.valueStrength).isNull();
    assertThat(spec.expireAfterWrite).isNull();
    assertThat(spec.expireAfterAccess).isEqualTo(Duration.ofDays(10));
    assertCacheBuilderEquivalence(
        Caffeine.newBuilder().expireAfterAccess(10L, TimeUnit.DAYS), Caffeine.from(spec));
  }

  @Test
  void parse_accessExpirationHours() {
    CaffeineSpec spec = parse("expireAfterAccess=150h");
    assertThat(spec.expireAfterAccess).isEqualTo(Duration.ofHours(150));
    assertCacheBuilderEquivalence(
        Caffeine.newBuilder().expireAfterAccess(150L, TimeUnit.HOURS), Caffeine.from(spec));
  }

  @Test
  void parse_accessExpirationMinutes() {
    CaffeineSpec spec = parse("expireAfterAccess=10m");
    assertThat(spec.expireAfterAccess).isEqualTo(Duration.ofMinutes(10));
    assertCacheBuilderEquivalence(
        Caffeine.newBuilder().expireAfterAccess(10L, TimeUnit.MINUTES),
        Caffeine.from(spec));
  }

  @Test
  void parse_accessExpirationSeconds() {
    CaffeineSpec spec = parse("expireAfterAccess=10s");
    assertThat(spec.expireAfterAccess).isEqualTo(Duration.ofSeconds(10));
    assertCacheBuilderEquivalence(
        Caffeine.newBuilder().expireAfterAccess(10L, TimeUnit.SECONDS),
        Caffeine.from(spec));
  }

  @Test
  void parse_accessExpirationRepeated() {
    assertThrows(IllegalArgumentException.class,
        () -> parse("expireAfterAccess=10s,expireAfterAccess=10m"));
  }

  @Test
  void parse_recordStats() {
    CaffeineSpec spec = parse("recordStats");
    assertThat(spec.recordStats).isTrue();
    assertCacheBuilderEquivalence(Caffeine.newBuilder().recordStats(), Caffeine.from(spec));
  }

  @Test
  void parse_recordStatsValueSpecified() {
    assertThrows(IllegalArgumentException.class, () -> parse("recordStats=True"));
  }

  @Test
  void parse_recordStatsRepeated() {
    assertThrows(IllegalArgumentException.class, () -> parse("recordStats,recordStats"));
  }

  @Test
  void parse_accessExpirationAndWriteExpiration() {
    CaffeineSpec spec = parse("expireAfterAccess=10s,expireAfterWrite=9m");
    assertThat(spec.expireAfterWrite).isEqualTo(Duration.ofMinutes(9));
    assertThat(spec.expireAfterAccess).isEqualTo(Duration.ofSeconds(10));
    assertCacheBuilderEquivalence(
        Caffeine.newBuilder()
          .expireAfterAccess(10L, TimeUnit.SECONDS)
          .expireAfterWrite(9L, TimeUnit.MINUTES),
        Caffeine.from(spec));
  }

  @Test
  void parse_multipleKeys() {
    CaffeineSpec spec = parse("initialCapacity=10,maximumSize=20,"
        + "weakKeys,weakValues,expireAfterAccess=10m,expireAfterWrite=1h");
    assertThat(spec.initialCapacity).isEqualTo(10);
    assertThat(spec.maximumSize).isEqualTo(20);
    assertThat(spec.maximumWeight).isEqualTo(UNSET_INT);
    assertThat(spec.keyStrength).isEqualTo(Strength.WEAK);
    assertThat(spec.valueStrength).isEqualTo(Strength.WEAK);
    assertThat(spec.expireAfterWrite).isEqualTo(Duration.ofHours(1));
    assertThat(spec.expireAfterAccess).isEqualTo(Duration.ofMinutes(10));
    Caffeine<?, ?> expected = Caffeine.newBuilder()
        .initialCapacity(10)
        .maximumSize(20)
        .weakKeys()
        .weakValues()
        .expireAfterAccess(10L, TimeUnit.MINUTES)
        .expireAfterWrite(1L, TimeUnit.HOURS);
    assertCacheBuilderEquivalence(expected, Caffeine.from(spec));
  }

  @Test
  void parse_whitespaceAllowed() {
    CaffeineSpec spec = parse(" initialCapacity=10,\nmaximumSize=20,\t\r"
        + "weakKeys \t ,softValues \n , \r  expireAfterWrite \t =  15s\n\n");
    assertThat(spec.initialCapacity).isEqualTo(10);
    assertThat(spec.maximumSize).isEqualTo(20);
    assertThat(spec.maximumWeight).isEqualTo(UNSET_INT);
    assertThat(spec.keyStrength).isEqualTo(Strength.WEAK);
    assertThat(spec.valueStrength).isEqualTo(Strength.SOFT);
    assertThat(spec.expireAfterWrite).isEqualTo(Duration.ofSeconds(15));
    assertThat(spec.expireAfterAccess).isNull();
    Caffeine<?, ?> expected = Caffeine.newBuilder()
        .initialCapacity(10)
        .maximumSize(20)
        .weakKeys()
        .softValues()
        .expireAfterWrite(15L, TimeUnit.SECONDS);
    assertCacheBuilderEquivalence(expected, Caffeine.from(spec));
  }

  @Test
  void parse_unknownKey() {
    assertThrows(IllegalArgumentException.class, () -> parse("foo=17"));
  }

  @Test @Disabled // Allowed by Caffeine
  void parse_extraCommaIsInvalid() {
    assertThrows(IllegalArgumentException.class, () -> parse("weakKeys,"));

    assertThrows(IllegalArgumentException.class, () -> parse(",weakKeys"));

    assertThrows(IllegalArgumentException.class, () -> parse("weakKeys,,softValues"));
  }

  @Test
  void equalsAndHashCode() {
    new EqualsTester()
        .addEqualityGroup(parse(""), parse(""))
        .addEqualityGroup(parse("initialCapacity=7"), parse("initialCapacity=7"))
        .addEqualityGroup(parse("initialCapacity=15"), parse("initialCapacity=15"))
        .addEqualityGroup(parse("maximumSize=7"), parse("maximumSize=7"))
        .addEqualityGroup(parse("maximumSize=15"), parse("maximumSize=15"))
        .addEqualityGroup(parse("maximumWeight=7"), parse("maximumWeight=7"))
        .addEqualityGroup(parse("maximumWeight=15"), parse("maximumWeight=15"))
        .addEqualityGroup(parse("expireAfterAccess=60s"), parse("expireAfterAccess=1m"))
        .addEqualityGroup(parse("expireAfterAccess=60m"), parse("expireAfterAccess=1h"))
        .addEqualityGroup(parse("expireAfterWrite=60s"), parse("expireAfterWrite=1m"))
        .addEqualityGroup(parse("expireAfterWrite=60m"), parse("expireAfterWrite=1h"))
        .addEqualityGroup(parse("weakKeys"), parse("weakKeys"))
        .addEqualityGroup(parse("softValues"), parse("softValues"))
        .addEqualityGroup(parse("weakValues"), parse("weakValues"))
        .addEqualityGroup(parse("recordStats"), parse("recordStats"))
        .testEquals();
  }

  @Test
  @SuppressWarnings("NullAway")
  void maximumWeight_withWeigher() {
    Caffeine<Object, Object> builder = Caffeine.from(parse("maximumWeight=9000"));
    assertThat(builder.weigher((k, v) -> 42).build(k -> null)).isNotNull();
  }

  @Test
  @SuppressWarnings("NullAway")
  void maximumWeight_withoutWeigher() {
    Caffeine<Object, Object> builder = Caffeine.from(parse("maximumWeight=9000"));
    assertThrows(IllegalStateException.class, () -> builder.build(k -> null));
  }

  @Test
  @SuppressWarnings("NullAway")
  void maximumSize_withWeigher() {
    Caffeine<Object, Object> builder = Caffeine.from(parse("maximumSize=9000"));
    assertThat(builder.weigher((k, v) -> 42).build(k -> null)).isNotNull();
  }

  @Test
  @SuppressWarnings("NullAway")
  void maximumSize_withoutWeigher() {
    Caffeine<Object, Object> builder = Caffeine.from(parse("maximumSize=9000"));
    assertThat(builder.build(k -> null)).isNotNull();
  }

  @Test
  void cacheBuilderFrom_string() {
    Caffeine<?, ?> fromString = Caffeine.from(
        "initialCapacity=10,maximumSize=20,weakKeys,weakValues,expireAfterAccess=10m");
    Caffeine<?, ?> expected = Caffeine.newBuilder()
        .initialCapacity(10)
        .maximumSize(20)
        .weakKeys()
        .weakValues()
        .expireAfterAccess(10L, TimeUnit.MINUTES);
    assertCacheBuilderEquivalence(expected, fromString);
  }

  private static CaffeineSpec parse(String specification) {
    return CaffeineSpec.parse(specification);
  }

  private static void assertCacheBuilderEquivalence(
      Caffeine<?, ?> expected, Caffeine<?, ?> actual) {
    assertWithMessage("expireAfterAccessNanos").that(actual.expireAfterAccessNanos)
        .isEqualTo(expected.expireAfterAccessNanos);
    assertWithMessage("expireAfterWriteNanos").that(actual.expireAfterWriteNanos)
        .isEqualTo(expected.expireAfterWriteNanos);
    assertWithMessage("initialCapacity").that(actual.initialCapacity)
        .isEqualTo(expected.initialCapacity);
    assertWithMessage("maximumSize").that(actual.maximumSize)
        .isEqualTo(expected.maximumSize);
    assertWithMessage("maximumWeight").that(actual.maximumWeight)
        .isEqualTo(expected.maximumWeight);
    assertWithMessage("refreshAfterWriteNanos").that(actual.refreshAfterWriteNanos)
        .isEqualTo(expected.refreshAfterWriteNanos);
    assertWithMessage("keyStrength").that(actual.keyStrength)
        .isEqualTo(expected.keyStrength);
    assertWithMessage("removalListener").that(actual.removalListener)
        .isEqualTo(expected.removalListener);
    assertWithMessage("weigher").that(actual.weigher)
        .isEqualTo(expected.weigher);
    assertWithMessage("valueStrength").that(actual.valueStrength)
        .isEqualTo(expected.valueStrength);
    assertWithMessage("statsCounterSupplier").that(actual.statsCounterSupplier)
        .isEqualTo(expected.statsCounterSupplier);
    assertWithMessage("ticker").that(actual.ticker)
        .isEqualTo(expected.ticker);
    assertWithMessage("recordStats").that(actual.isRecordingStats())
        .isEqualTo(expected.isRecordingStats());
  }
}
