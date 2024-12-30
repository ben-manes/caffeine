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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Caffeine.Strength;
import com.google.common.testing.EqualsTester;

import junit.framework.TestCase;

/**
 * A port of Guava's CacheBuilderSpecTest.
 * TODO(user): tests of a few invalid input conditions, boundary conditions.
 *
 * @author Adam Winer
 */
@SuppressWarnings({"PMD.DetachedTestCase",
    "PMD.UnitTestShouldUseTestAnnotation", "PreferJavaTimeOverload"})
public class CaffeineSpecGuavaTest extends TestCase {

  public void testParse_empty() {
    CaffeineSpec spec = parse("");
    assertThat(spec.initialCapacity).isEqualTo(UNSET_INT);
    assertThat(spec.maximumSize).isEqualTo(UNSET_INT);
    assertThat(spec.maximumWeight).isEqualTo(UNSET_INT);
    assertNull(spec.keyStrength);
    assertNull(spec.valueStrength);
    assertNull(spec.expireAfterAccess);
    assertNull(spec.expireAfterWrite);
    assertNull(spec.refreshAfterWrite);
    assertCaffeineEquivalence(Caffeine.newBuilder(), Caffeine.from(spec));
  }

  public void testParse_initialCapacity() {
    CaffeineSpec spec = parse("initialCapacity=10");
    assertThat(spec.initialCapacity).isEqualTo(10);
    assertThat(spec.maximumSize).isEqualTo(UNSET_INT);
    assertThat(spec.maximumWeight).isEqualTo(UNSET_INT);
    assertNull(spec.keyStrength);
    assertNull(spec.valueStrength);
    assertNull(spec.expireAfterWrite);
    assertNull(spec.expireAfterAccess);
    assertNull(spec.refreshAfterWrite);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().initialCapacity(10), Caffeine.from(spec));
  }

  public void testParse_initialCapacityRepeated() {
    assertThrows(IllegalArgumentException.class,
        () -> parse("initialCapacity=10, initialCapacity=20"));
  }

  public void testParse_maximumSize() {
    CaffeineSpec spec = parse("maximumSize=9000");
    assertThat(spec.initialCapacity).isEqualTo(UNSET_INT);
    assertThat(spec.maximumSize).isEqualTo(9000);
    assertNull(spec.keyStrength);
    assertNull(spec.valueStrength);
    assertNull(spec.expireAfterWrite);
    assertNull(spec.expireAfterAccess);
    assertNull(spec.refreshAfterWrite);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().maximumSize(9000), Caffeine.from(spec));
  }

  public void testParse_maximumSizeRepeated() {
    assertThrows(IllegalArgumentException.class, () -> parse("maximumSize=10, maximumSize=20"));
  }

  public void testParse_maximumWeight() {
    CaffeineSpec spec = parse("maximumWeight=9000");
    assertThat(spec.initialCapacity).isEqualTo(UNSET_INT);
    assertThat(spec.maximumWeight).isEqualTo(9000);
    assertNull(spec.keyStrength);
    assertNull(spec.valueStrength);
    assertNull(spec.expireAfterWrite);
    assertNull(spec.expireAfterAccess);
    assertNull(spec.refreshAfterWrite);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().maximumWeight(9000), Caffeine.from(spec));
  }

  public void testParse_maximumWeightRepeated() {
    assertThrows(IllegalArgumentException.class, () -> parse("maximumWeight=10, maximumWeight=20"));
  }

  public void testParse_maximumSizeAndMaximumWeight() {
    assertThrows(IllegalArgumentException.class, () -> parse("maximumSize=10, maximumWeight=20"));
  }

  public void testParse_weakKeys() {
    CaffeineSpec spec = parse("weakKeys");
    assertThat(spec.initialCapacity).isEqualTo(UNSET_INT);
    assertThat(spec.maximumSize).isEqualTo(UNSET_INT);
    assertThat(spec.maximumWeight).isEqualTo(UNSET_INT);
    assertThat(spec.keyStrength).isEqualTo(Strength.WEAK);
    assertNull(spec.valueStrength);
    assertNull(spec.expireAfterWrite);
    assertNull(spec.expireAfterAccess);
    assertNull(spec.refreshAfterWrite);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().weakKeys(), Caffeine.from(spec));
  }

  public void testParse_weakKeysCannotHaveValue() {
    assertThrows(IllegalArgumentException.class, () -> parse("weakKeys=true"));
  }

  public void testParse_repeatedKeyStrength() {
    assertThrows(IllegalArgumentException.class, () -> parse("weakKeys, weakKeys"));
  }

  public void testParse_softValues() {
    CaffeineSpec spec = parse("softValues");
    assertThat(spec.initialCapacity).isEqualTo(UNSET_INT);
    assertThat(spec.maximumSize).isEqualTo(UNSET_INT);
    assertThat(spec.maximumWeight).isEqualTo(UNSET_INT);
    assertNull(spec.keyStrength);
    assertThat(spec.valueStrength).isEqualTo(Strength.SOFT);
    assertNull(spec.expireAfterWrite);
    assertNull(spec.expireAfterAccess);
    assertNull(spec.refreshAfterWrite);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().softValues(), Caffeine.from(spec));
  }

  public void testParse_softValuesCannotHaveValue() {
    assertThrows(IllegalArgumentException.class, () -> parse("softValues=true"));
  }

  public void testParse_weakValues() {
    CaffeineSpec spec = parse("weakValues");
    assertThat(spec.initialCapacity).isEqualTo(UNSET_INT);
    assertThat(spec.maximumSize).isEqualTo(UNSET_INT);
    assertThat(spec.maximumWeight).isEqualTo(UNSET_INT);
    assertNull(spec.keyStrength);
    assertThat(spec.valueStrength).isEqualTo(Strength.WEAK);
    assertNull(spec.expireAfterWrite);
    assertNull(spec.expireAfterAccess);
    assertNull(spec.refreshAfterWrite);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().weakValues(), Caffeine.from(spec));
  }

  public void testParse_weakValuesCannotHaveValue() {
    assertThrows(IllegalArgumentException.class, () -> parse("weakValues=true"));
  }

  public void testParse_repeatedValueStrength() {
    assertThrows(IllegalArgumentException.class, () -> parse("softValues, softValues"));

    assertThrows(IllegalArgumentException.class, () -> parse("softValues, weakValues"));

    assertThrows(IllegalArgumentException.class, () -> parse("weakValues, softValues"));

    assertThrows(IllegalArgumentException.class, () -> parse("weakValues, weakValues"));
  }

  public void testParse_writeExpirationDays() {
    CaffeineSpec spec = parse("expireAfterWrite=10d");
    assertThat(spec.initialCapacity).isEqualTo(UNSET_INT);
    assertThat(spec.maximumSize).isEqualTo(UNSET_INT);
    assertThat(spec.maximumWeight).isEqualTo(UNSET_INT);
    assertNull(spec.keyStrength);
    assertNull(spec.valueStrength);
    assertThat(spec.expireAfterWrite).isEqualTo(Duration.ofDays(10));
    assertNull(spec.expireAfterAccess);
    assertNull(spec.refreshAfterWrite);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().expireAfterWrite(10L, TimeUnit.DAYS), Caffeine.from(spec));
  }

  public void testParse_writeExpirationHours() {
    CaffeineSpec spec = parse("expireAfterWrite=150h");
    assertThat(spec.expireAfterWrite).isEqualTo(Duration.ofHours(150));
    assertCaffeineEquivalence(
        Caffeine.newBuilder().expireAfterWrite(150L, TimeUnit.HOURS), Caffeine.from(spec));
  }

  public void testParse_writeExpirationMinutes() {
    CaffeineSpec spec = parse("expireAfterWrite=10m");
    assertThat(spec.expireAfterWrite).isEqualTo(Duration.ofMinutes(10));
    assertCaffeineEquivalence(
        Caffeine.newBuilder().expireAfterWrite(10L, TimeUnit.MINUTES), Caffeine.from(spec));
  }

  public void testParse_writeExpirationSeconds() {
    CaffeineSpec spec = parse("expireAfterWrite=10s");
    assertThat(spec.expireAfterWrite).isEqualTo(Duration.ofSeconds(10));
    assertCaffeineEquivalence(
        Caffeine.newBuilder().expireAfterWrite(10L, TimeUnit.SECONDS), Caffeine.from(spec));
  }

  public void testParse_writeExpirationRepeated() {
    assertThrows(IllegalArgumentException.class,
        () -> parse("expireAfterWrite=10s,expireAfterWrite=10m"));
  }

  public void testParse_accessExpirationDays() {
    CaffeineSpec spec = parse("expireAfterAccess=10d");
    assertThat(spec.initialCapacity).isEqualTo(UNSET_INT);
    assertThat(spec.maximumSize).isEqualTo(UNSET_INT);
    assertThat(spec.maximumWeight).isEqualTo(UNSET_INT);
    assertNull(spec.keyStrength);
    assertNull(spec.valueStrength);
    assertNull(spec.expireAfterWrite);
    assertThat(spec.expireAfterAccess).isEqualTo(Duration.ofDays(10));
    assertCaffeineEquivalence(
        Caffeine.newBuilder().expireAfterAccess(10L, TimeUnit.DAYS), Caffeine.from(spec));
  }

  public void testParse_accessExpirationHours() {
    CaffeineSpec spec = parse("expireAfterAccess=150h");
    assertThat(spec.expireAfterAccess).isEqualTo(Duration.ofHours(150));
    assertCaffeineEquivalence(
        Caffeine.newBuilder().expireAfterAccess(150L, TimeUnit.HOURS), Caffeine.from(spec));
  }

  public void testParse_accessExpirationMinutes() {
    CaffeineSpec spec = parse("expireAfterAccess=10m");
    assertThat(spec.expireAfterAccess).isEqualTo(Duration.ofMinutes(10));
    assertCaffeineEquivalence(
        Caffeine.newBuilder().expireAfterAccess(10L, TimeUnit.MINUTES),
        Caffeine.from(spec));
  }

  public void testParse_accessExpirationSeconds() {
    CaffeineSpec spec = parse("expireAfterAccess=10s");
    assertThat(spec.expireAfterAccess).isEqualTo(Duration.ofSeconds(10));
    assertCaffeineEquivalence(
        Caffeine.newBuilder().expireAfterAccess(10L, TimeUnit.SECONDS),
        Caffeine.from(spec));
  }

  public void testParse_accessExpirationRepeated() {
    assertThrows(IllegalArgumentException.class,
        () -> parse("expireAfterAccess=10s,expireAfterAccess=10m"));
  }

  public void testParse_recordStats() {
    CaffeineSpec spec = parse("recordStats");
    assertTrue(spec.recordStats);
    assertCaffeineEquivalence(Caffeine.newBuilder().recordStats(), Caffeine.from(spec));
  }

  public void testParse_recordStatsValueSpecified() {
    assertThrows(IllegalArgumentException.class, () -> parse("recordStats=True"));
  }

  public void testParse_recordStatsRepeated() {
    assertThrows(IllegalArgumentException.class, () -> parse("recordStats,recordStats"));
  }

  public void testParse_accessExpirationAndWriteExpiration() {
    CaffeineSpec spec = parse("expireAfterAccess=10s,expireAfterWrite=9m");
    assertThat(spec.expireAfterWrite).isEqualTo(Duration.ofMinutes(9));
    assertThat(spec.expireAfterAccess).isEqualTo(Duration.ofSeconds(10));
    assertCaffeineEquivalence(
        Caffeine.newBuilder()
          .expireAfterAccess(10L, TimeUnit.SECONDS)
          .expireAfterWrite(9L, TimeUnit.MINUTES),
        Caffeine.from(spec));
  }

  public void testParse_multipleKeys() {
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
    assertCaffeineEquivalence(expected, Caffeine.from(spec));
  }

  public void testParse_whitespaceAllowed() {
    CaffeineSpec spec = parse(" initialCapacity=10,\nmaximumSize=20,\t\r"
        + "weakKeys \t ,softValues \n , \r  expireAfterWrite \t =  15s\n\n");
    assertThat(spec.initialCapacity).isEqualTo(10);
    assertThat(spec.maximumSize).isEqualTo(20);
    assertThat(spec.maximumWeight).isEqualTo(UNSET_INT);
    assertThat(spec.keyStrength).isEqualTo(Strength.WEAK);
    assertThat(spec.valueStrength).isEqualTo(Strength.SOFT);
    assertThat(spec.expireAfterWrite).isEqualTo(Duration.ofSeconds(15));
    assertNull(spec.expireAfterAccess);
    Caffeine<?, ?> expected = Caffeine.newBuilder()
        .initialCapacity(10)
        .maximumSize(20)
        .weakKeys()
        .softValues()
        .expireAfterWrite(15L, TimeUnit.SECONDS);
    assertCaffeineEquivalence(expected, Caffeine.from(spec));
  }

  public void testParse_unknownKey() {
    assertThrows(IllegalArgumentException.class, () -> parse("foo=17"));
  }

  // Allowed by Caffeine
  @SuppressWarnings("MemberName")
  public void disabled_testParse_extraCommaIsInvalid() {
    assertThrows(IllegalArgumentException.class, () -> parse("weakKeys,"));

    assertThrows(IllegalArgumentException.class, () -> parse(",weakKeys"));

    assertThrows(IllegalArgumentException.class, () -> parse("weakKeys,,softValues"));
  }

  public void testEqualsAndHashCode() {
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

  @SuppressWarnings("NullAway")
  public void testMaximumWeight_withWeigher() {
    Caffeine<Object, Object> builder = Caffeine.from(parse("maximumWeight=9000"));
    assertThat(builder.weigher((k, v) -> 42).build(k -> null)).isNotNull();
  }

  @SuppressWarnings("NullAway")
  public void testMaximumWeight_withoutWeigher() {
    Caffeine<Object, Object> builder = Caffeine.from(parse("maximumWeight=9000"));
    assertThrows(IllegalStateException.class, () -> builder.build(k -> null));
  }

  @SuppressWarnings("NullAway")
  public void testMaximumSize_withWeigher() {
    Caffeine<Object, Object> builder = Caffeine.from(parse("maximumSize=9000"));
    assertThat(builder.weigher((k, v) -> 42).build(k -> null)).isNotNull();
  }

  @SuppressWarnings("NullAway")
  public void testMaximumSize_withoutWeigher() {
    Caffeine<Object, Object> builder = Caffeine.from(parse("maximumSize=9000"));
    assertThat(builder.build(k -> null)).isNotNull();
  }

  public void testCaffeineFrom_string() {
    Caffeine<?, ?> fromString = Caffeine.from(
        "initialCapacity=10,maximumSize=20,weakKeys,weakValues,expireAfterAccess=10m");
    Caffeine<?, ?> expected = Caffeine.newBuilder()
        .initialCapacity(10)
        .maximumSize(20)
        .weakKeys()
        .weakValues()
        .expireAfterAccess(10L, TimeUnit.MINUTES);
    assertCaffeineEquivalence(expected, fromString);
  }

  private static CaffeineSpec parse(String specification) {
    return CaffeineSpec.parse(specification);
  }

  private static void assertCaffeineEquivalence(Caffeine<?, ?> a, Caffeine<?, ?> b) {
    assertEquals("expireAfterAccessNanos", a.expireAfterAccessNanos, b.expireAfterAccessNanos);
    assertEquals("expireAfterWriteNanos", a.expireAfterWriteNanos, b.expireAfterWriteNanos);
    assertEquals("initialCapacity", a.initialCapacity, b.initialCapacity);
    assertEquals("maximumSize", a.maximumSize, b.maximumSize);
    assertEquals("maximumWeight", a.maximumWeight, b.maximumWeight);
    assertEquals("refreshNanos", a.refreshAfterWriteNanos, b.refreshAfterWriteNanos);
    assertEquals("keyStrength", a.keyStrength, b.keyStrength);
    assertEquals("removalListener", a.removalListener, b.removalListener);
    assertEquals("weigher", a.weigher, b.weigher);
    assertEquals("valueStrength", a.valueStrength, b.valueStrength);
    assertEquals("statsCounterSupplier", a.statsCounterSupplier, b.statsCounterSupplier);
    assertEquals("ticker", a.ticker, b.ticker);
    assertEquals("recordStats", a.isRecordingStats(), b.isRecordingStats());
  }
}
