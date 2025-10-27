/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.cache.Caffeine.UNSET_INT;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static java.math.BigInteger.ONE;
import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.Caffeine.Strength;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.testing.EqualsTester;

/**
 * A test for the specification parser.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class CaffeineSpecTest {
  static final long UNSET_LONG = UNSET_INT;

  @Test
  public void parseInt() {
    parseNumber(CaffeineSpec::parseInt);

    assertThat(CaffeineSpec.parseInt("key", Integer.toString(Integer.MAX_VALUE)))
        .isEqualTo(Integer.MAX_VALUE);
    assertThat(CaffeineSpec.parseInt("key", Integer.toString(Integer.MIN_VALUE)))
        .isEqualTo(Integer.MIN_VALUE);
    assertThrows(IllegalArgumentException.class, () ->
        CaffeineSpec.parseInt("key", Long.toString(Integer.MAX_VALUE + 1L)));
    assertThrows(IllegalArgumentException.class, () ->
        CaffeineSpec.parseInt("key", Long.toString(Integer.MIN_VALUE - 1L)));
  }

  @Test
  public void parseLong() {
    parseNumber(CaffeineSpec::parseLong);

    assertThat(CaffeineSpec.parseLong("key", Long.toString(Integer.MAX_VALUE)))
        .isEqualTo(Integer.MAX_VALUE);
    assertThat(CaffeineSpec.parseLong("key", Long.toString(Integer.MIN_VALUE)))
        .isEqualTo(Integer.MIN_VALUE);
    assertThat(CaffeineSpec.parseLong("key", Long.toString(Integer.MAX_VALUE + 1L)))
        .isEqualTo(Integer.MAX_VALUE + 1L);
    assertThat(CaffeineSpec.parseLong("key", Long.toString(Integer.MIN_VALUE - 1L)))
        .isEqualTo(Integer.MIN_VALUE - 1L);
    assertThat(CaffeineSpec.parseLong("key", Long.toString(Long.MAX_VALUE)))
        .isEqualTo(Long.MAX_VALUE);
    assertThat(CaffeineSpec.parseLong("key", Long.toString(Long.MIN_VALUE)))
        .isEqualTo(Long.MIN_VALUE);
    assertThrows(IllegalArgumentException.class, () ->
        CaffeineSpec.parseLong("key", BigInteger.valueOf(Long.MAX_VALUE).add(ONE).toString()));
    assertThrows(IllegalArgumentException.class, () ->
        CaffeineSpec.parseLong("key", BigInteger.valueOf(Long.MIN_VALUE).subtract(ONE).toString()));
  }

  private static void parseNumber(BiFunction<String, String, Number> parser) {
    var invalid = Arrays.asList(null, "", "_52", "52_", "-_52",
        "+_52", "52-", "52+", "52_-", "52_+", "value");
    for (var value : invalid) {
      assertThrows(IllegalArgumentException.class, () -> parser.apply("key", value));
    }
    var valid = List.of("5_2", "5_______2", "52", "-52", "+52", "052", "0_52");
    for (var value : valid) {
      assertThat(CaffeineSpec.parseInt("key", value)).isEqualTo(value.startsWith("-") ? -52 : 52);
    }
  }

  @Test
  @SuppressWarnings("NullAway")
  public void parseDuration_exception() {
    // TimeUnit
    assertThrows(IllegalArgumentException.class,
        () -> CaffeineSpec.parseTimeUnit("key", ""));
    assertThrows(IllegalArgumentException.class,
        () -> CaffeineSpec.parseTimeUnit("key", null));
    assertThrows(IllegalArgumentException.class,
        () -> CaffeineSpec.parseTimeUnit("key", "value"));

    // Duration
    assertThrows(IllegalArgumentException.class,
        () -> CaffeineSpec.parseDuration("key", null));
    assertThrows(IllegalArgumentException.class,
        () -> CaffeineSpec.parseDuration("key", ""));
    assertThrows(IllegalArgumentException.class,
        () -> CaffeineSpec.parseDuration("key", "value"));

    // ISO
    assertThrows(IllegalArgumentException.class,
        () -> CaffeineSpec.parseIsoDuration("key", "value"));
    assertThrows(IllegalArgumentException.class,
        () -> CaffeineSpec.parseDuration("key", "-PT7H3M"));
    assertThrows(IllegalArgumentException.class,
        () -> CaffeineSpec.parseDuration("key", "p3xyz"));

    // Simple
    assertThrows(IllegalArgumentException.class,
        () -> CaffeineSpec.parseSimpleDuration("key", "value"));
    assertThrows(IllegalArgumentException.class,
        () -> CaffeineSpec.parseDuration("key", "-1s"));
    assertThrows(IllegalArgumentException.class,
        () -> CaffeineSpec.parseDuration("key", "xyzs"));
    assertThrows(IllegalArgumentException.class,
        () -> CaffeineSpec.parseDuration("key", "1xyzs"));
  }

  @Test
  @SuppressWarnings("NullAway")
  public void parse_exception() {
    assertThrows(NullPointerException.class, () -> CaffeineSpec.parse(null));
    assertThrows(IllegalArgumentException.class, () -> CaffeineSpec.parse("="));
    assertThrows(IllegalArgumentException.class, () -> CaffeineSpec.parse("=="));
    assertThrows(IllegalArgumentException.class, () -> CaffeineSpec.parse("key="));
    assertThrows(IllegalArgumentException.class, () -> CaffeineSpec.parse("=value"));
    assertThrows(IllegalArgumentException.class, () -> CaffeineSpec.parse("key=value="));
    assertThrows(IllegalArgumentException.class, () -> CaffeineSpec.parse("\u00A0"));
    assertThrows(IllegalArgumentException.class, () -> CaffeineSpec.parse("\u200B"));
    assertThrows(IllegalArgumentException.class, () -> CaffeineSpec.parse("\u2060"));
    assertThrows(IllegalArgumentException.class, () -> CaffeineSpec.parse("\uFEFF"));
  }

  @Test
  public void toBuilder_invalidKeyStrength() {
    var spec = CaffeineSpec.parse("");
    spec.keyStrength = Strength.SOFT;
    assertThrows(IllegalStateException.class, spec::toBuilder);
  }

  @Test
  public void equals() {
    var configurations = Lists.cartesianProduct(IntStream.range(0, 9)
        .mapToObj(i -> ImmutableList.of(false, true)).collect(toImmutableList()));
    var hashes = new LinkedHashSet<Integer>();
    var tester = new EqualsTester();
    for (var configuration : configurations) {
      var spec = CaffeineSpec.parse("");
      spec.refreshAfterWrite = configuration.get(0) ? Duration.ofMinutes(1) : null;
      spec.expireAfterAccess = configuration.get(1) ? Duration.ofMinutes(1) : null;
      spec.expireAfterWrite = configuration.get(2) ? Duration.ofMinutes(1) : null;
      spec.valueStrength = configuration.get(3) ? Strength.WEAK : null;
      spec.keyStrength = configuration.get(4) ? Strength.WEAK : null;
      spec.initialCapacity = configuration.get(5) ? 1 : UNSET_INT;
      spec.maximumWeight = configuration.get(6) ? 1 : UNSET_INT ;
      spec.maximumSize = configuration.get(7) ? 1 : UNSET_INT;
      spec.recordStats = configuration.get(8);
      tester.addEqualityGroup(spec);
      hashes.add(spec.hashCode());
    }
    tester.testEquals();
    assertThat(hashes).hasSize(configurations.size());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      initialCapacity = {InitialCapacity.DEFAULT, InitialCapacity.FULL},
      compute = Compute.SYNC, removalListener = Listener.DISABLED)
  public void seconds(CacheContext context) {
    runScenarios(context, new Epoch(TimeUnit.SECONDS, "s"));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      initialCapacity = {InitialCapacity.DEFAULT, InitialCapacity.FULL},
      compute = Compute.SYNC, removalListener = Listener.DISABLED)
  public void minutes(CacheContext context) {
    runScenarios(context, new Epoch(TimeUnit.MINUTES, "m"));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      initialCapacity = {InitialCapacity.DEFAULT, InitialCapacity.FULL},
      compute = Compute.SYNC, removalListener = Listener.DISABLED)
  public void hours(CacheContext context) {
    runScenarios(context, new Epoch(TimeUnit.HOURS, "h"));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      initialCapacity = {InitialCapacity.DEFAULT, InitialCapacity.FULL},
      compute = Compute.SYNC, removalListener = Listener.DISABLED)
  public void days(CacheContext context) {
    runScenarios(context, new Epoch(TimeUnit.DAYS, "d"));
  }

  private static void runScenarios(CacheContext context, Epoch epoch) {
    runTest(context, epoch, duration -> epoch.toUnitString(duration).toLowerCase(US));
    runTest(context, epoch, duration -> epoch.toUnitString(duration).toUpperCase(US));
    runTest(context, epoch, duration -> epoch.truncate(duration).toString().toLowerCase(US));
    runTest(context, epoch, duration -> epoch.truncate(duration).toString().toUpperCase(US));
  }

  private static void runTest(CacheContext context,
      Epoch epoch, Function<Duration, String> formatter) {
    CaffeineSpec spec = toSpec(context, formatter);
    Caffeine<Object, Object> builder = Caffeine.from(spec);

    checkInitialCapacity(spec, context, builder);
    checkMaximumWeight(spec, context, builder);
    checkMaximumSize(spec, context, builder);
    checkWeakKeys(spec, context, builder);
    checkValueStrength(spec, context, builder);
    checkExpireAfterWrite(spec, context, builder, epoch);
    checkExpireAfterAccess(spec, context, builder, epoch);
    checkRefreshAfterWrite(spec, context, builder, epoch);

    assertThat(spec).isEqualTo(CaffeineSpec.parse(spec.toParsableString()));
    assertThat(spec).isEqualTo(reflectivelyConstruct(spec.toParsableString()));
    assertThat(spec).isEqualTo(CaffeineSpec.parse(spec.toParsableString().replaceAll(",", ",,")));
  }

  static CaffeineSpec toSpec(CacheContext context, Function<Duration, String> formatter) {
    var options = new ArrayList<String>();
    if (context.initialCapacity() != InitialCapacity.DEFAULT) {
      options.add("initialCapacity=" + context.initialCapacity().size());
    }
    if (context.maximum() != Maximum.DISABLED) {
      String key = context.isWeighted() ? "maximumWeight" : "maximumSize";
      options.add(key + "=" + context.maximum().max());
    }
    if (context.isWeakKeys()) {
      options.add("weakKeys");
    }
    if (context.isWeakValues()) {
      options.add("weakValues");
    }
    if (context.isSoftValues()) {
      options.add("softValues");
    }
    if (context.expireAfterWrite() != Expire.DISABLED) {
      String duration = formatter.apply(context.expireAfterWrite().duration());
      options.add("expireAfterWrite=" + duration);
    }
    if (context.expireAfterAccess() != Expire.DISABLED) {
      String duration = formatter.apply(context.expireAfterAccess().duration());
      options.add("expireAfterAccess=" + duration);
    }
    if (context.refreshAfterWrite() != Expire.DISABLED) {
      String duration = formatter.apply(context.refreshAfterWrite().duration());
      options.add("refreshAfterWrite=" + duration);
    }
    if (context.isRecordingStats()) {
      options.add("recordStats");
    }
    Collections.shuffle(options);
    String specString = Joiner.on(',').join(options);
    return CaffeineSpec.parse(specString);
  }

  static void checkInitialCapacity(CaffeineSpec spec,
      CacheContext context, Caffeine<?, ?> builder) {
    if (context.initialCapacity() == InitialCapacity.DEFAULT) {
      assertThat(spec.initialCapacity).isEqualTo(UNSET_INT);
      assertThat(builder.initialCapacity).isEqualTo(UNSET_INT);
    } else {
      assertThat(spec.initialCapacity).isEqualTo(context.initialCapacity().size());
      assertThat(builder.initialCapacity).isEqualTo(context.initialCapacity().size());
    }
  }

  static void checkMaximumSize(CaffeineSpec spec, CacheContext context, Caffeine<?, ?> builder) {
    if ((context.maximum() == Maximum.DISABLED) || context.isWeighted()) {
      assertThat(spec.maximumSize).isEqualTo(UNSET_LONG);
      assertThat(builder.maximumSize).isEqualTo(UNSET_LONG);
    } else {
      assertThat(spec.maximumSize).isEqualTo(context.maximum().max());
      assertThat(builder.maximumSize).isEqualTo(context.maximum().max());
      assertThrows(IllegalArgumentException.class, () ->
          CaffeineSpec.parse(spec.toParsableString() + ",maximumSize=1"));
      assertThrows(IllegalArgumentException.class, () ->
          CaffeineSpec.parse(spec.toParsableString() + ",maximumWeight=1"));
    }
  }

  static void checkMaximumWeight(CaffeineSpec spec, CacheContext context, Caffeine<?, ?> builder) {
    if ((context.maximum() == Maximum.DISABLED) || !context.isWeighted()) {
      assertThat(spec.maximumWeight).isEqualTo(UNSET_LONG);
      assertThat(builder.maximumWeight).isEqualTo(UNSET_LONG);
    } else {
      assertThat(spec.maximumWeight).isEqualTo(context.maximum().max());
      assertThat(builder.maximumWeight).isEqualTo(context.maximum().max());
      assertThrows(IllegalArgumentException.class, () ->
          CaffeineSpec.parse(spec.toParsableString() + ",maximumSize=1"));
      assertThrows(IllegalArgumentException.class, () ->
          CaffeineSpec.parse(spec.toParsableString() + ",maximumWeight=1"));
    }
  }

  static void checkWeakKeys(CaffeineSpec spec, CacheContext context, Caffeine<?, ?> builder) {
    if (context.isStrongKeys()) {
      assertThat(spec.keyStrength).isNull();
      assertThat(builder.keyStrength).isNull();
    } else {
      assertThat(spec.keyStrength).isEqualTo(Strength.WEAK);
      assertThat(builder.keyStrength).isEqualTo(Strength.WEAK);
      assertThrows(IllegalArgumentException.class, () ->
          CaffeineSpec.parse(spec.toParsableString() + ",weakKeys"));
    }
  }

  static void checkValueStrength(CaffeineSpec spec, CacheContext context, Caffeine<?, ?> builder) {
    if (context.isStrongValues()) {
      assertThat(spec.valueStrength).isNull();
      assertThat(builder.valueStrength).isNull();
      return;
    }

    if (context.isWeakValues()) {
      assertThat(spec.valueStrength).isEqualTo(Strength.WEAK);
      assertThat(builder.valueStrength).isEqualTo(Strength.WEAK);
    } else if (context.isSoftValues()) {
      assertThat(spec.valueStrength).isEqualTo(Strength.SOFT);
      assertThat(builder.valueStrength).isEqualTo(Strength.SOFT);
    }
    assertThrows(IllegalArgumentException.class, () ->
        CaffeineSpec.parse(spec.toParsableString() + ",weakValues"));
    assertThrows(IllegalArgumentException.class, () ->
        CaffeineSpec.parse(spec.toParsableString() + ",softValues"));
  }

  static void checkExpireAfterAccess(CaffeineSpec spec,
      CacheContext context, Caffeine<?, ?> builder, Epoch epoch) {
    if (context.expireAfterAccess() == Expire.DISABLED) {
      assertThat(spec.expireAfterAccess).isNull();
      assertThat(builder.expireAfterAccessNanos).isEqualTo(UNSET_LONG);
    } else {
      var duration = epoch.truncate(context.expireAfterAccess().duration());
      assertThat(spec.expireAfterAccess).isEqualTo(duration);
      assertThat(builder.expireAfterAccessNanos).isEqualTo(duration.toNanos());
      assertThrows(IllegalArgumentException.class, () ->
          CaffeineSpec.parse(spec.toParsableString() + ",expireAfterAccess=1s"));
      assertThrows(IllegalArgumentException.class, () ->
          CaffeineSpec.parse(spec.toParsableString() + ",expireAfterAccess=" + Duration.ofDays(1)));
    }
  }

  static void checkExpireAfterWrite(CaffeineSpec spec,
      CacheContext context, Caffeine<?, ?> builder, Epoch epoch) {
    if (context.expireAfterWrite() == Expire.DISABLED) {
      assertThat(spec.expireAfterWrite).isNull();
      assertThat(builder.expireAfterWriteNanos).isEqualTo(UNSET_LONG);
    } else {
      var duration = epoch.truncate(context.expireAfterWrite().duration());
      assertThat(spec.expireAfterWrite).isEqualTo(duration);
      assertThat(builder.expireAfterWriteNanos).isEqualTo(duration.toNanos());
      assertThrows(IllegalArgumentException.class, () ->
          CaffeineSpec.parse(spec.toParsableString() + ",expireAfterWrite=1s"));
      assertThrows(IllegalArgumentException.class, () ->
          CaffeineSpec.parse(spec.toParsableString() + ",expireAfterWrite=" + Duration.ofDays(1)));
    }
  }

  static void checkRefreshAfterWrite(CaffeineSpec spec,
      CacheContext context, Caffeine<?, ?> builder, Epoch epoch) {
    if (context.refreshAfterWrite() == Expire.DISABLED) {
      assertThat(spec.refreshAfterWrite).isNull();
      assertThat(builder.refreshAfterWriteNanos).isEqualTo(UNSET_LONG);
    } else {
      var duration = epoch.truncate(context.refreshAfterWrite().duration());
      assertThat(spec.refreshAfterWrite).isEqualTo(duration);
      assertThat(builder.refreshAfterWriteNanos).isEqualTo(duration.toNanos());
      assertThrows(IllegalArgumentException.class, () ->
          CaffeineSpec.parse(spec.toParsableString() + ",refreshAfterWrite=1s"));
      assertThrows(IllegalArgumentException.class, () ->
          CaffeineSpec.parse(spec.toParsableString() + ",refreshAfterWrite=" + Duration.ofDays(1)));
    }
  }

  @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
  static CaffeineSpec reflectivelyConstruct(String spec) {
    try {
      var constructor = CaffeineSpec.class.getDeclaredConstructor(String.class);
      constructor.setAccessible(true);
      return constructor.newInstance(spec);
    } catch (ReflectiveOperationException e) {
      throw new LinkageError(e.getMessage(), e);
    }
  }

  static final class Epoch {
    final TimeUnit unit;
    final String symbol;

    public Epoch(TimeUnit unit, String symbol) {
      this.symbol = requireNonNull(symbol);
      this.unit = requireNonNull(unit);
    }
    Duration truncate(Duration duration) {
      return Duration.ofNanos(unit.toNanos(unit.convert(duration)));
    }
    public String toUnitString(Duration duration) {
      return unit.convert(duration) + symbol;
    }
  }
}
