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
import static com.github.benmanes.caffeine.cache.CaffeineSpec.parse;

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
@SuppressWarnings({"PreferJavaTimeOverload", "CheckReturnValue"})
public class CaffeineSpecGuavaTest extends TestCase {

  public void testParse_empty() {
    CaffeineSpec spec = parse("");
    assertEquals(spec.initialCapacity, UNSET_INT);
    assertEquals(spec.maximumSize, UNSET_INT);
    assertEquals(spec.maximumWeight, UNSET_INT);
    assertNull(spec.keyStrength);
    assertNull(spec.valueStrength);
    assertNull(spec.expireAfterAccess);
    assertNull(spec.expireAfterWrite);
    assertNull(spec.refreshAfterWrite);
    assertCaffeineEquivalence(Caffeine.newBuilder(), Caffeine.from(spec));
  }

  public void testParse_initialCapacity() {
    CaffeineSpec spec = parse("initialCapacity=10");
    assertEquals(10, spec.initialCapacity);
    assertEquals(spec.maximumSize, UNSET_INT);
    assertEquals(spec.maximumWeight, UNSET_INT);
    assertNull(spec.keyStrength);
    assertNull(spec.valueStrength);
    assertNull(spec.expireAfterWrite);
    assertNull(spec.expireAfterAccess);
    assertNull(spec.refreshAfterWrite);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().initialCapacity(10), Caffeine.from(spec));
  }

  public void testParse_initialCapacityRepeated() {
    try {
      parse("initialCapacity=10, initialCapacity=20");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  public void testParse_maximumSize() {
    CaffeineSpec spec = parse("maximumSize=9000");
    assertEquals(spec.initialCapacity, UNSET_INT);
    assertEquals(9000, spec.maximumSize);
    assertNull(spec.keyStrength);
    assertNull(spec.valueStrength);
    assertNull(spec.expireAfterWrite);
    assertNull(spec.expireAfterAccess);
    assertNull(spec.refreshAfterWrite);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().maximumSize(9000), Caffeine.from(spec));
  }

  public void testParse_maximumSizeRepeated() {
    try {
      parse("maximumSize=10, maximumSize=20");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  public void testParse_maximumWeight() {
    CaffeineSpec spec = parse("maximumWeight=9000");
    assertEquals(spec.initialCapacity, UNSET_INT);
    assertEquals(9000, spec.maximumWeight);
    assertNull(spec.keyStrength);
    assertNull(spec.valueStrength);
    assertNull(spec.expireAfterWrite);
    assertNull(spec.expireAfterAccess);
    assertNull(spec.refreshAfterWrite);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().maximumWeight(9000), Caffeine.from(spec));
  }

  public void testParse_maximumWeightRepeated() {
    try {
      parse("maximumWeight=10, maximumWeight=20");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  public void testParse_maximumSizeAndMaximumWeight() {
    try {
      parse("maximumSize=10, maximumWeight=20");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  public void testParse_weakKeys() {
    CaffeineSpec spec = parse("weakKeys");
    assertEquals(spec.initialCapacity, UNSET_INT);
    assertEquals(spec.maximumSize, UNSET_INT);
    assertEquals(spec.maximumWeight, UNSET_INT);
    assertEquals(Strength.WEAK, spec.keyStrength);
    assertNull(spec.valueStrength);
    assertNull(spec.expireAfterWrite);
    assertNull(spec.expireAfterAccess);
    assertNull(spec.refreshAfterWrite);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().weakKeys(), Caffeine.from(spec));
  }

  public void testParse_weakKeysCannotHaveValue() {
    try {
      parse("weakKeys=true");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  public void testParse_repeatedKeyStrength() {
    try {
      parse("weakKeys, weakKeys");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  public void testParse_softValues() {
    CaffeineSpec spec = parse("softValues");
    assertEquals(spec.initialCapacity, UNSET_INT);
    assertEquals(spec.maximumSize, UNSET_INT);
    assertEquals(spec.maximumWeight, UNSET_INT);
    assertNull(spec.keyStrength);
    assertEquals(Strength.SOFT, spec.valueStrength);
    assertNull(spec.expireAfterWrite);
    assertNull(spec.expireAfterAccess);
    assertNull(spec.refreshAfterWrite);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().softValues(), Caffeine.from(spec));
  }

  public void testParse_softValuesCannotHaveValue() {
    try {
      parse("softValues=true");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  public void testParse_weakValues() {
    CaffeineSpec spec = parse("weakValues");
    assertEquals(spec.initialCapacity, UNSET_INT);
    assertEquals(spec.maximumSize, UNSET_INT);
    assertEquals(spec.maximumWeight, UNSET_INT);
    assertNull(spec.keyStrength);
    assertEquals(Strength.WEAK, spec.valueStrength);
    assertNull(spec.expireAfterWrite);
    assertNull(spec.expireAfterAccess);
    assertNull(spec.refreshAfterWrite);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().weakValues(), Caffeine.from(spec));
  }

  public void testParse_weakValuesCannotHaveValue() {
    try {
      parse("weakValues=true");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  public void testParse_repeatedValueStrength() {
    try {
      parse("softValues, softValues");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }

    try {
      parse("softValues, weakValues");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }

    try {
      parse("weakValues, softValues");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }

    try {
      parse("weakValues, weakValues");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  public void testParse_writeExpirationDays() {
    CaffeineSpec spec = parse("expireAfterWrite=10d");
    assertEquals(spec.initialCapacity, UNSET_INT);
    assertEquals(spec.maximumSize, UNSET_INT);
    assertEquals(spec.maximumWeight, UNSET_INT);
    assertNull(spec.keyStrength);
    assertNull(spec.valueStrength);
    assertEquals(Duration.ofDays(10), spec.expireAfterWrite);
    assertNull(spec.expireAfterAccess);
    assertNull(spec.refreshAfterWrite);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().expireAfterWrite(10L, TimeUnit.DAYS), Caffeine.from(spec));
  }

  public void testParse_writeExpirationHours() {
    CaffeineSpec spec = parse("expireAfterWrite=150h");
    assertEquals(Duration.ofHours(150), spec.expireAfterWrite);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().expireAfterWrite(150L, TimeUnit.HOURS), Caffeine.from(spec));
  }

  public void testParse_writeExpirationMinutes() {
    CaffeineSpec spec = parse("expireAfterWrite=10m");
    assertEquals(Duration.ofMinutes(10), spec.expireAfterWrite);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().expireAfterWrite(10L, TimeUnit.MINUTES), Caffeine.from(spec));
  }

  public void testParse_writeExpirationSeconds() {
    CaffeineSpec spec = parse("expireAfterWrite=10s");
    assertEquals(Duration.ofSeconds(10), spec.expireAfterWrite);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().expireAfterWrite(10L, TimeUnit.SECONDS), Caffeine.from(spec));
  }

  public void testParse_writeExpirationRepeated() {
    try {
      parse(
          "expireAfterWrite=10s,expireAfterWrite=10m");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  public void testParse_accessExpirationDays() {
    CaffeineSpec spec = parse("expireAfterAccess=10d");
    assertEquals(spec.initialCapacity, UNSET_INT);
    assertEquals(spec.maximumSize, UNSET_INT);
    assertEquals(spec.maximumWeight, UNSET_INT);
    assertNull(spec.keyStrength);
    assertNull(spec.valueStrength);
    assertNull(spec.expireAfterWrite);
    assertEquals(Duration.ofDays(10), spec.expireAfterAccess);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().expireAfterAccess(10L, TimeUnit.DAYS), Caffeine.from(spec));
  }

  public void testParse_accessExpirationHours() {
    CaffeineSpec spec = parse("expireAfterAccess=150h");
    assertEquals(Duration.ofHours(150), spec.expireAfterAccess);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().expireAfterAccess(150L, TimeUnit.HOURS), Caffeine.from(spec));
  }

  public void testParse_accessExpirationMinutes() {
    CaffeineSpec spec = parse("expireAfterAccess=10m");
    assertEquals(Duration.ofMinutes(10), spec.expireAfterAccess);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().expireAfterAccess(10L, TimeUnit.MINUTES),
        Caffeine.from(spec));
  }

  public void testParse_accessExpirationSeconds() {
    CaffeineSpec spec = parse("expireAfterAccess=10s");
    assertEquals(Duration.ofSeconds(10), spec.expireAfterAccess);
    assertCaffeineEquivalence(
        Caffeine.newBuilder().expireAfterAccess(10L, TimeUnit.SECONDS),
        Caffeine.from(spec));
  }

  public void testParse_accessExpirationRepeated() {
    try {
      parse(
          "expireAfterAccess=10s,expireAfterAccess=10m");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  public void testParse_recordStats() {
    CaffeineSpec spec = parse("recordStats");
    assertTrue(spec.recordStats);
    assertCaffeineEquivalence(Caffeine.newBuilder().recordStats(), Caffeine.from(spec));
  }

  public void testParse_recordStatsValueSpecified() {
    try {
      parse("recordStats=True");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  public void testParse_recordStatsRepeated() {
    try {
      parse("recordStats,recordStats");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  public void testParse_accessExpirationAndWriteExpiration() {
    CaffeineSpec spec = parse("expireAfterAccess=10s,expireAfterWrite=9m");
    assertEquals(Duration.ofMinutes(9), spec.expireAfterWrite);
    assertEquals(Duration.ofSeconds(10), spec.expireAfterAccess);
    assertCaffeineEquivalence(
        Caffeine.newBuilder()
          .expireAfterAccess(10L, TimeUnit.SECONDS)
          .expireAfterWrite(9L, TimeUnit.MINUTES),
        Caffeine.from(spec));
  }

  public void testParse_multipleKeys() {
    CaffeineSpec spec = parse("initialCapacity=10,maximumSize=20,"
        + "weakKeys,weakValues,expireAfterAccess=10m,expireAfterWrite=1h");
    assertEquals(10, spec.initialCapacity);
    assertEquals(20, spec.maximumSize);
    assertEquals(spec.maximumWeight, UNSET_INT);
    assertEquals(Strength.WEAK, spec.keyStrength);
    assertEquals(Strength.WEAK, spec.valueStrength);
    assertEquals(Duration.ofHours(1), spec.expireAfterWrite);
    assertEquals(Duration.ofMinutes(10L), spec.expireAfterAccess);
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
    assertEquals(10, spec.initialCapacity);
    assertEquals(20, spec.maximumSize);
    assertEquals(spec.maximumWeight, UNSET_INT);
    assertEquals(Strength.WEAK, spec.keyStrength);
    assertEquals(Strength.SOFT, spec.valueStrength);
    assertEquals(Duration.ofSeconds(15), spec.expireAfterWrite);
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
    try {
      parse("foo=17");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  // Allowed by Caffeine
  public void disabled_testParse_extraCommaIsInvalid() {
    try {
      parse("weakKeys,");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }

    try {
      parse(",weakKeys");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }

    try {
      parse("weakKeys,,softValues");
      fail("Expected exception");
    } catch (IllegalArgumentException expected) {
      // expected
    }
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

  public void testMaximumWeight_withWeigher() {
    Caffeine<Object, Object> builder = Caffeine.from(parse("maximumWeight=9000"));
    builder.weigher((k, v) -> 42).build(k -> null);
  }

  public void testMaximumWeight_withoutWeigher() {
    Caffeine<Object, Object> builder = Caffeine.from(parse("maximumWeight=9000"));
    try {
      builder.build(k -> null);
      fail();
    } catch (IllegalStateException expected) {}
  }

  public void testMaximumSize_withWeigher() {
    Caffeine<Object, Object> builder = Caffeine.from(parse("maximumSize=9000"));
    builder.weigher((k, v) -> 42).build(k -> null);
  }

  public void testMaximumSize_withoutWeigher() {
    Caffeine<Object, Object> builder = Caffeine.from(parse("maximumSize=9000"));
    builder.build(k -> null);
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
