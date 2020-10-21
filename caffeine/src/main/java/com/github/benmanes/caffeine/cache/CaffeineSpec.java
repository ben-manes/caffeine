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

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

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
 * Durations are represented as either an ISO-8601 string using {@link Duration#parse(CharSequence)}
 * or by an integer followed by one of "d", "h", "m", or "s", representing days, hours, minutes, or
 * seconds respectively. There is currently no short syntax to request durations in milliseconds,
 * microseconds, or nanoseconds.
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

  @Nullable Strength keyStrength;
  @Nullable Strength valueStrength;
  @Nullable Duration expireAfterWrite;
  @Nullable Duration expireAfterAccess;
  @Nullable Duration refreshAfterWrite;

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
    if (expireAfterWrite != null) {
      builder.expireAfterWrite(expireAfterWrite);
    }
    if (expireAfterAccess != null) {
      builder.expireAfterAccess(expireAfterAccess);
    }
    if (refreshAfterWrite != null) {
      builder.refreshAfterWrite(refreshAfterWrite);
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
  @SuppressWarnings("StringSplitter")
  public static @NonNull CaffeineSpec parse(@NonNull String specification) {
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

    @SuppressWarnings("StringSplitter")
    String[] keyAndValue = option.split(SPLIT_KEY_VALUE);
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
  void initialCapacity(String key, @Nullable String value) {
    requireArgument(initialCapacity == UNSET_INT,
        "initial capacity was already set to %,d", initialCapacity);
    initialCapacity = parseInt(key, value);
  }

  /** Configures the maximum size. */
  void maximumSize(String key, @Nullable String value) {
    requireArgument(maximumSize == UNSET_INT,
        "maximum size was already set to %,d", maximumSize);
    requireArgument(maximumWeight == UNSET_INT,
        "maximum weight was already set to %,d", maximumWeight);
    maximumSize = parseLong(key, value);
  }

  /** Configures the maximum size. */
  void maximumWeight(String key, @Nullable String value) {
    requireArgument(maximumWeight == UNSET_INT,
        "maximum weight was already set to %,d", maximumWeight);
    requireArgument(maximumSize == UNSET_INT,
        "maximum size was already set to %,d", maximumSize);
    maximumWeight = parseLong(key, value);
  }

  /** Configures the keys as weak references. */
  void weakKeys(@Nullable String value) {
    requireArgument(value == null, "weak keys does not take a value");
    requireArgument(keyStrength == null, "weak keys was already set");
    keyStrength = Strength.WEAK;
  }

  /** Configures the value as weak or soft references. */
  void valueStrength(String key, @Nullable String value, Strength strength) {
    requireArgument(value == null, "%s does not take a value", key);
    requireArgument(valueStrength == null, "%s was already set to %s", key, valueStrength);
    valueStrength = strength;
  }

  /** Configures expire after access. */
  void expireAfterAccess(String key, @Nullable String value) {
    requireArgument(expireAfterAccess == null, "expireAfterAccess was already set");
    expireAfterAccess = parseDuration(key, value);
  }

  /** Configures expire after write. */
  void expireAfterWrite(String key, @Nullable String value) {
    requireArgument(expireAfterWrite == null, "expireAfterWrite was already set");
    expireAfterWrite = parseDuration(key, value);
  }

  /** Configures refresh after write. */
  void refreshAfterWrite(String key, @Nullable String value) {
    requireArgument(refreshAfterWrite == null, "refreshAfterWrite was already set");
    refreshAfterWrite = parseDuration(key, value);
  }

  /** Configures the value as weak or soft references. */
  void recordStats(@Nullable String value) {
    requireArgument(value == null, "record stats does not take a value");
    requireArgument(!recordStats, "record stats was already set");
    recordStats = true;
  }

  /** Returns a parsed int value. */
  static int parseInt(String key, @Nullable String value) {
    requireArgument((value != null) && !value.isEmpty(), "value of key %s was omitted", key);
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(String.format(
          "key %s value was set to %s, must be an integer", key, value), e);
    }
  }

  /** Returns a parsed long value. */
  static long parseLong(String key, @Nullable String value) {
    requireArgument((value != null) && !value.isEmpty(), "value of key %s was omitted", key);
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(String.format(
          "key %s value was set to %s, must be a long", key, value), e);
    }
  }

  /** Returns a parsed duration value. */
  static Duration parseDuration(String key, @Nullable String value) {
    requireArgument((value != null) && !value.isEmpty(), "value of key %s omitted", key);

    @SuppressWarnings("NullAway")
    boolean isIsoFormat = value.contains("p") || value.contains("P");
    if (isIsoFormat) {
      Duration duration = Duration.parse(value);
      requireArgument(!duration.isNegative(),
          "key %s invalid format; was %s, but the duration cannot be negative", key, value);
      return duration;
    }

    @SuppressWarnings("NullAway")
    long duration = parseLong(key, value.substring(0, value.length() - 1));
    TimeUnit unit = parseTimeUnit(key, value);
    return Duration.ofNanos(unit.toNanos(duration));
  }

  /** Returns a parsed {@link TimeUnit} value. */
  static TimeUnit parseTimeUnit(String key, @Nullable String value) {
    requireArgument((value != null) && !value.isEmpty(), "value of key %s omitted", key);
    @SuppressWarnings("NullAway")
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
        && Objects.equals(expireAfterWrite, spec.expireAfterWrite)
        && Objects.equals(expireAfterAccess, spec.expireAfterAccess)
        && Objects.equals(refreshAfterWrite, spec.refreshAfterWrite);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        initialCapacity, maximumSize, maximumWeight, keyStrength, valueStrength,
        recordStats, expireAfterWrite, expireAfterAccess, refreshAfterWrite);
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
