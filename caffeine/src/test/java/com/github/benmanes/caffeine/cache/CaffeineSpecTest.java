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
import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;

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
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Writer;
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

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = {InitialCapacity.DEFAULT, InitialCapacity.FULL},
      population = Population.EMPTY, compute = Compute.SYNC, writer = Writer.DISABLED,
      removalListener = Listener.DEFAULT, implementation = Implementation.Caffeine)
  public void seconds(CacheContext context) {
    runScenarios(context, new Epoch(TimeUnit.SECONDS, "s"));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = {InitialCapacity.DEFAULT, InitialCapacity.FULL},
      population = Population.EMPTY, compute = Compute.SYNC, writer = Writer.DISABLED,
      removalListener = Listener.DEFAULT, implementation = Implementation.Caffeine)
  public void minutes(CacheContext context) {
    runScenarios(context, new Epoch(TimeUnit.MINUTES, "m"));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = {InitialCapacity.DEFAULT, InitialCapacity.FULL},
      population = Population.EMPTY, compute = Compute.SYNC, writer = Writer.DISABLED,
      removalListener = Listener.DEFAULT, implementation = Implementation.Caffeine)
  public void hours(CacheContext context) {
    runScenarios(context, new Epoch(TimeUnit.HOURS, "h"));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = {InitialCapacity.DEFAULT, InitialCapacity.FULL},
      population = Population.EMPTY, compute = Compute.SYNC, writer = Writer.DISABLED,
      removalListener = Listener.DEFAULT, implementation = Implementation.Caffeine)
  public void days(CacheContext context) {
    runScenarios(context, new Epoch(TimeUnit.DAYS, "d"));
  }

  private void runScenarios(CacheContext context, Epoch epoch) {
    runTest(context, epoch, nanos -> epoch.toUnitString(nanos).toLowerCase(US));
    runTest(context, epoch, nanos -> epoch.toUnitString(nanos).toUpperCase(US));
    runTest(context, epoch, nanos -> epoch.toDuration(nanos).toString().toLowerCase(US));
    runTest(context, epoch, nanos -> epoch.toDuration(nanos).toString().toUpperCase(US));
  }

  private void runTest(CacheContext context, Epoch epoch, LongFunction<String> nanosToString) {
    CaffeineSpec spec = toSpec(context, epoch, nanosToString);
    Caffeine<Object, Object> builder = Caffeine.from(spec);

    checkInitialCapacity(spec, context, builder);
    checkMaximumWeight(spec, context, builder);
    checkMaximumSize(spec, context, builder);
    checkWeakKeys(spec, context, builder);
    checkValueStrength(spec, context, builder);
    checkExpireAfterWrite(spec, context, builder, epoch);
    checkExpireAfterAccess(spec, context, builder, epoch);
    checkRefreshAfterWrite(spec, context, builder, epoch);

    assertThat(spec, is(equalTo(CaffeineSpec.parse(spec.toParsableString()))));
  }

  static CaffeineSpec toSpec(CacheContext context,
      Epoch epoch, LongFunction<String> nanosToString) {
    List<String> options = new ArrayList<>();
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
      String duration = nanosToString.apply(context.expireAfterWrite().timeNanos());
      options.add("expireAfterWrite=" + duration);
    }
    if (context.expireAfterAccess() != Expire.DISABLED) {
      String duration = nanosToString.apply(context.expireAfterAccess().timeNanos());
      options.add("expireAfterAccess=" + duration);
    }
    if (context.refreshAfterWrite() != Expire.DISABLED) {
      String duration = nanosToString.apply(context.refreshAfterWrite().timeNanos());
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
      assertThat(spec.initialCapacity, is(UNSET_INT));
      assertThat(builder.initialCapacity, is(UNSET_INT));
    } else {
      assertThat(spec.initialCapacity, is(context.initialCapacity().size()));
      assertThat(builder.initialCapacity, is(context.initialCapacity().size()));
    }
  }

  static void checkMaximumSize(CaffeineSpec spec, CacheContext context, Caffeine<?, ?> builder) {
    if (context.isWeighted()) {
      assertThat(spec.maximumSize, is(UNSET_LONG));
      assertThat(builder.maximumSize, is(UNSET_LONG));
      return;
    }
    if (context.maximum() == Maximum.DISABLED) {
      assertThat(spec.maximumSize, is(UNSET_LONG));
      assertThat(builder.maximumSize, is(UNSET_LONG));
    } else {
      assertThat(spec.maximumSize, is(context.maximum().max()));
      assertThat(builder.maximumSize, is(context.maximum().max()));
    }
  }

  static void checkMaximumWeight(CaffeineSpec spec, CacheContext context, Caffeine<?, ?> builder) {
    if (!context.isWeighted()) {
      assertThat(spec.maximumWeight, is(UNSET_LONG));
      assertThat(builder.maximumWeight, is(UNSET_LONG));
      return;
    }
    if (context.maximum() == Maximum.DISABLED) {
      assertThat(spec.maximumWeight, is(UNSET_LONG));
      assertThat(builder.maximumWeight, is(UNSET_LONG));
    } else {
      assertThat(spec.maximumWeight, is(context.maximum().max()));
      assertThat(builder.maximumWeight, is(context.maximum().max()));
    }
  }

  static void checkWeakKeys(CaffeineSpec spec, CacheContext context, Caffeine<?, ?> builder) {
    if (context.isWeakKeys()) {
      assertThat(spec.keyStrength, is(Strength.WEAK));
      assertThat(builder.keyStrength, is(Strength.WEAK));
    } else {
      assertThat(spec.keyStrength, is(nullValue()));
      assertThat(builder.keyStrength, is(nullValue()));
    }
  }

  static void checkValueStrength(CaffeineSpec spec, CacheContext context, Caffeine<?, ?> builder) {
    if (context.isWeakValues()) {
      assertThat(spec.valueStrength, is(Strength.WEAK));
      assertThat(builder.valueStrength, is(Strength.WEAK));
    } else if (context.isSoftValues()) {
      assertThat(spec.valueStrength, is(Strength.SOFT));
      assertThat(builder.valueStrength, is(Strength.SOFT));
    } else {
      assertThat(spec.valueStrength, is(nullValue()));
      assertThat(builder.valueStrength, is(nullValue()));
    }
  }

  static void checkExpireAfterAccess(CaffeineSpec spec,
      CacheContext context, Caffeine<?, ?> builder, Epoch epoch) {
    if (context.expireAfterAccess() == Expire.DISABLED) {
      assertThat(spec.expireAfterAccess, is(nullValue()));
      assertThat(builder.expireAfterAccessNanos, is(UNSET_LONG));
    } else {
      long nanos = context.expireAfterAccess().timeNanos();
      assertThat(spec.expireAfterAccess, is(epoch.toDuration(nanos)));
      assertThat(builder.expireAfterAccessNanos, is(epoch.truncate(nanos)));
    }
  }

  static void checkExpireAfterWrite(CaffeineSpec spec,
      CacheContext context, Caffeine<?, ?> builder, Epoch epoch) {
    if (context.expireAfterWrite() == Expire.DISABLED) {
      assertThat(spec.expireAfterWrite, is(nullValue()));
      assertThat(builder.expireAfterWriteNanos, is(UNSET_LONG));
    } else {
      long nanos = context.expireAfterWrite().timeNanos();
      assertThat(spec.expireAfterWrite, is(epoch.toDuration(nanos)));
      assertThat(builder.expireAfterWriteNanos, is(epoch.truncate(nanos)));
    }
  }

  static void checkRefreshAfterWrite(CaffeineSpec spec,
      CacheContext context, Caffeine<?, ?> builder, Epoch epoch) {
    if (context.refreshAfterWrite() == Expire.DISABLED) {
      assertThat(spec.refreshAfterWrite, is(nullValue()));
      assertThat(builder.refreshAfterWriteNanos, is(UNSET_LONG));
    } else {
      long nanos = context.refreshAfterWrite().timeNanos();
      assertThat(spec.refreshAfterWrite, is(epoch.toDuration(nanos)));
      assertThat(builder.refreshAfterWriteNanos, is(epoch.truncate(nanos)));
    }
  }

  static final class Epoch {
    final TimeUnit unit;
    final String symbol;

    public Epoch(TimeUnit unit, String symbol) {
      this.symbol = requireNonNull(symbol);
      this.unit = requireNonNull(unit);
    }
    long truncate(long nanos) {
      return unit.toNanos(unit.convert(nanos, TimeUnit.NANOSECONDS));
    }
    public String toUnitString(long nanos) {
      return unit.convert(nanos, TimeUnit.NANOSECONDS) + symbol;
    }
    public Duration toDuration(long nanos) {
      return Duration.ofNanos(truncate(nanos));
    }
  }
}
