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
import static com.github.benmanes.caffeine.cache.Caffeine.requireArgument;
import static com.github.benmanes.caffeine.cache.Caffeine.requireState;
import static java.util.Objects.requireNonNull;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.Caffeine.Strength;

/**
 * A specification of a {@link Caffeine} builder configuration.
 * <p>
 * {@code CaffeineSpec} supports parsing configuration off of a string, which makes it especially
 * useful for command-line configuration of a {@code Caffeine} builder.
 * <p>
 * The string syntax is a series of comma-separated keys or key-value pairs, each corresponding to a
 * {@code Caffeine} builder method.
 * <ul>
 *   <li>{@code initialCapacity=[integer]}: sets {@link Caffeine#initialCapacity}.
 *   <li>{@code maximumSize=[long]}: sets {@link Caffeine#maximumSize}.
 *   <li>{@code maximumWeight=[long]}: sets {@link Caffeine#maximumWeight}.
 *   <li>{@code expireAfterAccess=[duration]}: sets {@link Caffeine#expireAfterAccess}.
 *   <li>{@code expireAfterWrite=[duration]}: sets {@link Caffeine#expireAfterWrite}.
 *   <li>{@code refreshAfterWrite=[duration]}: sets {@link Caffeine#refreshAfterWrite}.
 *   <li>{@code weakKeys}: sets {@link Caffeine#weakKeys}.
 *   <li>{@code weakValues}: sets {@link Caffeine#weakValues}.
 *   <li>{@code softValues}: sets {@link Caffeine#softValues}.
 *   <li>{@code recordStats}: sets {@link Caffeine#recordStats}.
 * </ul>
 * <p>
 * Durations are represented by an integer, followed by one of "d", "h", "m", or "s", representing
 * days, hours, minutes, or seconds respectively. There is currently no syntax to request expiration
 * in milliseconds, microseconds, or nanoseconds.
 * <p>
 * Whitespace before and after commas and equal signs is ignored. Keys may not be repeated; it is
 * also illegal to use the following pairs of keys in a single value:
 * <ul>
 *   <li>{@code maximumSize} and {@code maximumWeight}
 *   <li>{@code weakValues} and {@code softValues}
 * </ul>
 * <p>
 * {@code CaffeineSpec} does not support configuring {@code Caffeine} methods with non-value
 * parameters. These must be configured in code.
 * <p>
 * A new {@code Caffeine} builder can be instantiated from a {@code CaffeineSpec} using
 * {@link Caffeine#from(CaffeineSpec)} or {@link Caffeine#from(String)}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeineSpec {
  static final String SPLIT_OPTIONS = ",";
  static final String SPLIT_KEY_VALUE = "=";

  final String specification;

  int initialCapacity = UNSET_INT;
  long maximumWeight = UNSET_INT;
  long maximumSize = UNSET_INT;
  boolean recordStats;

  Strength keyStrength;
  Strength valueStrength;

  long expireAfterAccessDuration = UNSET_INT;
  TimeUnit expireAfterAccessTimeUnit;

  long expireAfterWriteDuration = UNSET_INT;
  TimeUnit expireAfterWriteTimeUnit;

  long refreshAfterWriteDuration = UNSET_INT;
  TimeUnit refreshAfterWriteTimeUnit;

  private CaffeineSpec(String specification) {
    this.specification = requireNonNull(specification);
  }

  /**
   * Returns a {@link Caffeine} builder configured according to this specification.
   *
   * @return a builder configured to the specification
   */
  Caffeine<Object, Object> toBuilder() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    if (initialCapacity != UNSET_INT) {
      builder.initialCapacity(initialCapacity);
    }
    if (maximumSize != UNSET_INT) {
      builder.maximumSize(maximumSize);
    }
    if (maximumWeight != UNSET_INT) {
      builder.maximumWeight(maximumWeight);
    }
    if (keyStrength != null) {
      requireState(keyStrength == Strength.WEAK);
      builder.weakKeys();
    }
    if (valueStrength != null) {
      if (valueStrength == Strength.WEAK) {
        builder.weakValues();
      } else if (valueStrength == Strength.SOFT) {
        builder.softValues();
      } else {
        throw new IllegalStateException();
      }
    }
    if (expireAfterAccessDuration != UNSET_INT) {
      builder.expireAfterAccess(expireAfterAccessDuration, expireAfterAccessTimeUnit);
    }
    if (expireAfterWriteDuration != UNSET_INT) {
      builder.expireAfterWrite(expireAfterWriteDuration, expireAfterWriteTimeUnit);
    }
    if (refreshAfterWriteDuration != UNSET_INT) {
      builder.refreshAfterWrite(refreshAfterWriteDuration, refreshAfterWriteTimeUnit);
    }
    if (recordStats) {
      builder.recordStats();
    }
    return builder;
  }

  /**
   * Creates a CaffeineSpec from a string.
   *
   * @param specification the string form
   * @return the parsed specification
   */
  public static CaffeineSpec parse(String specification) {
    CaffeineSpec spec = new CaffeineSpec(specification);
    for (String option : specification.split(SPLIT_OPTIONS)) {
      spec.parseOption(option.trim());
    }
    return spec;
  }

  /** Parses and applies the configuration option. */
  void parseOption(String option) {
    if (option.isEmpty()) {
      return;
    }

    String[] keyAndValue = option.split(SPLIT_KEY_VALUE);
    requireArgument(keyAndValue.length != 0, "blank key-value pair");
    requireArgument(keyAndValue.length <= 2,
        "key-value pair %s with more than one equals sign", option);

    String key = keyAndValue[0].trim();
    String value = (keyAndValue.length == 1) ? null : keyAndValue[1].trim();

    configure(key, value);
  }

  /** Configures the setting. */
  void configure(String key, @Nullable String value) {
    switch (key) {
      case "initialCapacity":
        initialCapacity(key, value);
        return;
      case "maximumSize":
        maximumSize(key, value);
        return;
      case "maximumWeight":
        maximumWeight(key, value);
        return;
      case "weakKeys":
        weakKeys(value);
        return;
      case "weakValues":
        valueStrength(key, value, Strength.WEAK);
        return;
      case "softValues":
        valueStrength(key, value, Strength.SOFT);
        return;
      case "expireAfterAccess":
        expireAfterAccess(key, value);
        return;
      case "expireAfterWrite":
        expireAfterWrite(key, value);
        return;
      case "refreshAfterWrite":
        refreshAfterWrite(key, value);
        return;
      case "recordStats":
        recordStats(value);
        return;
      default:
        throw new IllegalArgumentException("Unknown key " + key);
    }
  }

  /** Configures the initial capacity. */
  void initialCapacity(String key, String value) {
    initialCapacity(parseInt(key, value));
  }

  /** Configures the initial capacity. */
  @Nonnull
  public CaffeineSpec initialCapacity(int capacity) {
    requireArgument(initialCapacity == UNSET_INT,
        "initial capacity was already set to %,d", initialCapacity);
    this.initialCapacity = capacity;
    return this;
  }

  /** Configures the maximum size. */
  void maximumSize(String key, String value) {
    maximumSize(parseLong(key, value));
  }

  /** Configures the maximum size. */
  @Nonnull
  public CaffeineSpec maximumSize(long size) {
    requireArgument(maximumSize == UNSET_INT,
        "maximum size was already set to %,d", maximumSize);
    requireArgument(maximumWeight == UNSET_INT,
        "maximum weight was already set to %,d", maximumWeight);
    this.maximumSize = size;
    return this;
  }

  /** Configures the maximum size. */
  void maximumWeight(String key, String value) {
    maximumWeight(parseLong(key, value));
  }

  /** Configures the maximum size. */
  @Nonnull
  public CaffeineSpec maximumWeight(long weight) {
    requireArgument(maximumWeight == UNSET_INT,
        "maximum weight was already set to %,d", maximumWeight);
    requireArgument(maximumSize == UNSET_INT,
        "maximum size was already set to %,d", maximumSize);
    this.maximumWeight = weight;
    return this;
  }

  /** Configures the keys as weak references. */
  void weakKeys(@Nullable String value) {
    requireArgument(value == null, "weak keys does not take a value");
    requireArgument(keyStrength == null, "weak keys was already set");
    weakKeys();
  }

  /** Configures the key strength. */
  @Nonnull
  public CaffeineSpec weakKeys() {
    requireArgument(keyStrength == null, "key strength was already set");
    keyStrength = Strength.WEAK;
    return this;
  }

  /** Configures the value as weak or soft references. */
  void valueStrength(String key, @Nullable String value, Strength strength) {
    requireArgument(value == null, "%s does not take a value", key);
    valueStrength(strength);
  }

  /** Configures the valueStrength as weak or soft references. */
  @Nonnull
  public CaffeineSpec valueStrength(Strength valueStrength) {
    requireNonNull(valueStrength, "valueStrength strength required");
    requireArgument(this.valueStrength == null, "valueStrength was already set to %s", this.valueStrength);
    this.valueStrength = valueStrength;
    return this;
  }

  /** Configures expire after access. */
  void expireAfterAccess(String key, String value) {
    expireAfterAccess(parseTimeUnit(key, value), parseDuration(key, value));
  }

  /** Configures expire after access. */
  @Nonnull
  public CaffeineSpec expireAfterAccess(TimeUnit timeUnit, long duration) {
    requireNonNull(timeUnit, "timeUnit required");
    requireArgument(expireAfterAccessDuration == UNSET_INT, "expireAfterAccess was already set");
    expireAfterAccessTimeUnit = timeUnit;
    expireAfterAccessDuration = duration;
    return this;
  }

  /** Configures expire after write. */
  void expireAfterWrite(String key, String value) {
    expireAfterWrite(parseTimeUnit(key, value), parseDuration(key, value));
  }

  /** Configures expire after write. */
  @Nonnull
  public CaffeineSpec expireAfterWrite(TimeUnit timeUnit, long duration) {
    requireNonNull(timeUnit, "timeUnit required");
    requireArgument(expireAfterWriteDuration == UNSET_INT, "expireAfterWrite was already set");
    expireAfterWriteTimeUnit = timeUnit;
    expireAfterWriteDuration = duration;
    return this;
  }

  /** Configures refresh after write. */
  void refreshAfterWrite(String key, String value) {
    refreshAfterWrite(parseTimeUnit(key, value), parseDuration(key, value));
  }

  /** Configures refresh after write. */
  @Nonnull
  public CaffeineSpec refreshAfterWrite(TimeUnit timeUnit, long duration) {
    requireArgument(refreshAfterWriteDuration == UNSET_INT, "refreshAfterWrite was already set");
    refreshAfterWriteTimeUnit = timeUnit;
    refreshAfterWriteDuration = duration;
    return this;
  }

  /** Configures the value as weak or soft references. */
  void recordStats(@Nullable String value) {
    requireArgument(value == null, "record stats does not take a value");
    recordStats();
  }

  /** Configures the value as weak or soft references. */
  @Nonnull
  public CaffeineSpec recordStats() {
    requireArgument(!recordStats, "record stats was already set");
    recordStats = true;
    return this;
  }

  /** Returns a parsed int value. */
  static int parseInt(String key, String value) {
    requireArgument(value != null && !value.isEmpty(), "value of key %s was omitted", key);
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(String.format(
          "key %s value was set to %s, must be an integer", key, value), e);
    }
  }

  /** Returns a parsed long value. */
  static long parseLong(String key, String value) {
    requireArgument(value != null && !value.isEmpty(), "value of key %s was omitted", key);
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(String.format(
          "key %s value was set to %s, must be a long", key, value), e);
    }
  }

  /** Returns a parsed duration value. */
  static long parseDuration(String key, String value) {
    requireArgument(value != null && !value.isEmpty(), "value of key %s omitted", key);
    return parseLong(key, value.substring(0, value.length() - 1));
  }

  /** Returns a parsed {@link TimeUnit} value. */
  static TimeUnit parseTimeUnit(String key, String value) {
    requireArgument(value != null && !value.isEmpty(), "value of key %s omitted", key);
    char lastChar = Character.toLowerCase(value.charAt(value.length() - 1));
    switch (lastChar) {
      case 'd':
        return TimeUnit.DAYS;
      case 'h':
        return TimeUnit.HOURS;
      case 'm':
        return TimeUnit.MINUTES;
      case 's':
        return TimeUnit.SECONDS;
      default:
        throw new IllegalArgumentException(String.format(
            "key %s invalid format; was %s, must end with one of [dDhHmMsS]", key, value));
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    } else if (!(o instanceof CaffeineSpec)) {
      return false;
    }
    CaffeineSpec spec = (CaffeineSpec) o;
    return Objects.equals(initialCapacity, spec.initialCapacity)
        && Objects.equals(maximumSize, spec.maximumSize)
        && Objects.equals(maximumWeight, spec.maximumWeight)
        && Objects.equals(keyStrength, spec.keyStrength)
        && Objects.equals(valueStrength, spec.valueStrength)
        && Objects.equals(recordStats, spec.recordStats)
        && (durationInNanos(expireAfterAccessDuration, expireAfterAccessTimeUnit) ==
            durationInNanos(spec.expireAfterAccessDuration, spec.expireAfterAccessTimeUnit))
        && (durationInNanos(expireAfterWriteDuration, expireAfterWriteTimeUnit) ==
            durationInNanos(spec.expireAfterWriteDuration, spec.expireAfterWriteTimeUnit))
        && (durationInNanos(refreshAfterWriteDuration, refreshAfterWriteTimeUnit) ==
            durationInNanos(spec.refreshAfterWriteDuration, spec.refreshAfterWriteTimeUnit));
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        initialCapacity, maximumSize, maximumWeight, keyStrength, valueStrength, recordStats,
        durationInNanos(expireAfterAccessDuration, expireAfterAccessTimeUnit),
        durationInNanos(expireAfterWriteDuration, expireAfterWriteTimeUnit),
        durationInNanos(refreshAfterWriteDuration, refreshAfterWriteTimeUnit));
  }

  /** Converts an expiration duration/unit pair into a single long for hashing and equality. */
  static long durationInNanos(long duration, @Nullable TimeUnit unit) {
    return (unit == null) ? UNSET_INT : unit.toNanos(duration);
  }

  /**
   * Returns a string that can be used to parse an equivalent {@code CaffeineSpec}. The order and
   * form of this representation is not guaranteed, except that parsing its output will produce a
   * {@code CaffeineSpec} equal to this instance.
   *
   * @return a string representation of this specification
   */
  public String toParsableString() {
    return specification;
  }

  /**
   * Returns a string representation for this {@code CaffeineSpec} instance. The form of this
   * representation is not guaranteed.
   */
  @Override
  public String toString() {
    return getClass().getSimpleName() + '{' + toParsableString() + '}';
  }
}
