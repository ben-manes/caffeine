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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
  public void specifications(CacheContext context) {
    CaffeineSpec spec = toSpec(context);
    Caffeine<Object, Object> builder = Caffeine.from(spec);

    checkInitialCapacity(spec, context, builder);
    checkMaximumWeight(spec, context, builder);
    checkMaximumSize(spec, context, builder);
    checkWeakKeys(spec, context, builder);
    checkValueStrength(spec, context, builder);
    checkExpireAfterAccess(spec, context, builder);
    checkExpireAfterWrite(spec, context, builder);
    checkRefreshAfterWrite(spec, context, builder);

    assertThat(spec, is(equalTo(CaffeineSpec.parse(spec.toParsableString()))));
  }

  static CaffeineSpec toSpec(CacheContext context) {
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
    if (context.expireAfterAccess() != Expire.DISABLED) {
      long durationMin = TimeUnit.NANOSECONDS.toMinutes(context.expireAfterAccess().timeNanos());
      options.add("expireAfterAccess=" + durationMin + "m");
    }
    if (context.expireAfterWrite() != Expire.DISABLED) {
      long durationMin = TimeUnit.NANOSECONDS.toMinutes(context.expireAfterWrite().timeNanos());
      options.add("expireAfterWrite=" + durationMin + "m");
    }
    if (context.refreshAfterWrite() != Expire.DISABLED) {
      long durationMin = TimeUnit.NANOSECONDS.toMinutes(context.refreshAfterWrite().timeNanos());
      options.add("refreshAfterWrite=" + durationMin + "m");
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
      CacheContext context, Caffeine<?, ?> builder) {
    if (context.expireAfterAccess() == Expire.DISABLED) {
      assertThat(spec.expireAfterAccessDuration, is(UNSET_LONG));
      assertThat(spec.expireAfterAccessTimeUnit, is(nullValue()));
      assertThat(builder.expireAfterAccessNanos, is(UNSET_LONG));
    } else {
      long durationMin = TimeUnit.NANOSECONDS.toMinutes(context.expireAfterAccess().timeNanos());
      assertThat(spec.expireAfterAccessDuration, is(durationMin));
      assertThat(spec.expireAfterAccessTimeUnit, is(TimeUnit.MINUTES));
      assertThat(builder.expireAfterAccessNanos, is(TimeUnit.MINUTES.toNanos(durationMin)));
    }
  }

  static void checkExpireAfterWrite(CaffeineSpec spec,
      CacheContext context, Caffeine<?, ?> builder) {
    if (context.expireAfterWrite() == Expire.DISABLED) {
      assertThat(spec.expireAfterWriteDuration, is(UNSET_LONG));
      assertThat(spec.expireAfterWriteTimeUnit, is(nullValue()));
      assertThat(builder.expireAfterWriteNanos, is(UNSET_LONG));
    } else {
      long durationMin = TimeUnit.NANOSECONDS.toMinutes(context.expireAfterWrite().timeNanos());
      assertThat(spec.expireAfterWriteDuration, is(durationMin));
      assertThat(spec.expireAfterWriteTimeUnit, is(TimeUnit.MINUTES));
      assertThat(builder.expireAfterWriteNanos, is(TimeUnit.MINUTES.toNanos(durationMin)));
    }
  }

  static void checkRefreshAfterWrite(CaffeineSpec spec,
      CacheContext context, Caffeine<?, ?> builder) {
    if (context.refreshAfterWrite() == Expire.DISABLED) {
      assertThat(spec.refreshAfterWriteDuration, is(UNSET_LONG));
      assertThat(spec.refreshAfterWriteTimeUnit, is(nullValue()));
      assertThat(builder.refreshNanos, is(UNSET_LONG));
    } else {
      long durationMin = TimeUnit.NANOSECONDS.toMinutes(context.refreshAfterWrite().timeNanos());
      assertThat(spec.refreshAfterWriteDuration, is(durationMin));
      assertThat(spec.refreshAfterWriteTimeUnit, is(TimeUnit.MINUTES));
      assertThat(builder.refreshNanos, is(TimeUnit.MINUTES.toNanos(durationMin)));
    }
  }
}
