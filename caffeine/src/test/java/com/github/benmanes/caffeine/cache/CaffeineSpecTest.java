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
import static com.google.common.truth.Truth.assertThat;
import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;
import static org.junit.Assert.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Caffeine.Strength;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.google.common.base.Joiner;

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
  public void parseInt_exception() {
    assertThrows(IllegalArgumentException.class, () -> CaffeineSpec.parseInt("key", "value"));
  }

  @Test
  public void parseLong_exception() {
    assertThrows(IllegalArgumentException.class, () -> CaffeineSpec.parseLong("key", "value"));
  }

  @Test
  public void parseTimeUnit_exception() {
    assertThrows(IllegalArgumentException.class, () -> CaffeineSpec.parseTimeUnit("key", "value"));
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

  private void runScenarios(CacheContext context, Epoch epoch) {
    runTest(context, epoch, duration -> epoch.toUnitString(duration).toLowerCase(US));
    runTest(context, epoch, duration -> epoch.toUnitString(duration).toUpperCase(US));
    runTest(context, epoch, duration -> epoch.truncate(duration).toString().toLowerCase(US));
    runTest(context, epoch, duration -> epoch.truncate(duration).toString().toUpperCase(US));
  }

  private void runTest(CacheContext context, Epoch epoch, Function<Duration, String> formatter) {
    CaffeineSpec spec = toSpec(context, epoch, formatter);
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
  }

  static CaffeineSpec toSpec(CacheContext context,
      Epoch epoch, Function<Duration, String> formatter) {
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
    if (context.isWeighted()) {
      assertThat(spec.maximumSize).isEqualTo(UNSET_LONG);
      assertThat(builder.maximumSize).isEqualTo(UNSET_LONG);
      return;
    }
    if (context.maximum() == Maximum.DISABLED) {
      assertThat(spec.maximumSize).isEqualTo(UNSET_LONG);
      assertThat(builder.maximumSize).isEqualTo(UNSET_LONG);
    } else {
      assertThat(spec.maximumSize).isEqualTo(context.maximum().max());
      assertThat(builder.maximumSize).isEqualTo(context.maximum().max());
    }
  }

  static void checkMaximumWeight(CaffeineSpec spec, CacheContext context, Caffeine<?, ?> builder) {
    if (!context.isWeighted()) {
      assertThat(spec.maximumWeight).isEqualTo(UNSET_LONG);
      assertThat(builder.maximumWeight).isEqualTo(UNSET_LONG);
      return;
    }
    if (context.maximum() == Maximum.DISABLED) {
      assertThat(spec.maximumWeight).isEqualTo(UNSET_LONG);
      assertThat(builder.maximumWeight).isEqualTo(UNSET_LONG);
    } else {
      assertThat(spec.maximumWeight).isEqualTo(context.maximum().max());
      assertThat(builder.maximumWeight).isEqualTo(context.maximum().max());
    }
  }

  static void checkWeakKeys(CaffeineSpec spec, CacheContext context, Caffeine<?, ?> builder) {
    if (context.isWeakKeys()) {
      assertThat(spec.keyStrength).isEqualTo(Strength.WEAK);
      assertThat(builder.keyStrength).isEqualTo(Strength.WEAK);
    } else {
      assertThat(spec.keyStrength).isNull();
      assertThat(builder.keyStrength).isNull();
    }
  }

  static void checkValueStrength(CaffeineSpec spec, CacheContext context, Caffeine<?, ?> builder) {
    if (context.isWeakValues()) {
      assertThat(spec.valueStrength).isEqualTo(Strength.WEAK);
      assertThat(builder.valueStrength).isEqualTo(Strength.WEAK);
    } else if (context.isSoftValues()) {
      assertThat(spec.valueStrength).isEqualTo(Strength.SOFT);
      assertThat(builder.valueStrength).isEqualTo(Strength.SOFT);
    } else {
      assertThat(spec.valueStrength).isNull();
      assertThat(builder.valueStrength).isNull();
    }
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
