/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.math.NumberUtils.toInt;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

import org.apache.commons.lang3.Strings;

import com.github.benmanes.caffeine.cache.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.CacheSpec.Stats;

/**
 * The test generation options.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
final class Options {
  private static final Options SYSTEM_PROPERTY_OPTIONS = new Options(System.getProperties());

  private final Optional<Implementation> implementation;
  private final Optional<ReferenceType> values;
  private final Optional<ReferenceType> keys;
  private final Optional<Compute> compute;
  private final Optional<Stats> stats;
  private final boolean isFiltered;
  private final int shardCount;
  private final int shardIndex;

  private Options(Properties properties) {
    implementation = option(Implementation.class, "implementation", properties);
    values = option(ReferenceType.class, "values", properties);
    compute = option(Compute.class, "compute", properties);
    keys = option(ReferenceType.class, "keys", properties);
    stats = option(Stats.class, "stats", properties);
    shardIndex = toInt(System.getProperty("shardIndex"), 0);
    shardCount = Math.max(toInt(System.getProperty("shardCount"), 1), 1);
    isFiltered = Stream.of(implementation, compute, keys, values, stats)
        .anyMatch(Optional::isPresent);
  }

  private static <T extends Enum<T>> Optional<T> option(
      Class<T> enumClass, String property, Properties properties) {
    var value = properties.getProperty(property);
    if (isBlank(value)) {
      return Optional.empty();
    }
    for (var option : EnumSet.allOf(enumClass)) {
      if (Strings.CI.equals(option.name(), value)) {
        return Optional.of(option);
      }
    }
    throw new IllegalStateException(value + " not found in " + EnumSet.allOf(enumClass));
  }

  /** Returns the test options from the system properties. */
  public static Options fromSystemProperties() {
    return SYSTEM_PROPERTY_OPTIONS;
  }

  /** Returns the implementation filter, or all if unset. */
  public Optional<Implementation> implementation() {
    return implementation;
  }

  /** Returns the key reference type filter, or all if unset. */
  public Optional<ReferenceType> keys() {
    return keys;
  }

  /** Returns the value reference type filter, or all if unset. */
  public Optional<ReferenceType> values() {
    return values;
  }

  /** Returns the computation type filter, or all if unset. */
  public Optional<Compute> compute() {
    return compute;
  }

  /** Returns the statistics type filter, or all if unset. */
  public Optional<Stats> stats() {
    return stats;
  }

  /** Returns if any filters are applied. */
  public boolean isFiltered() {
    return isFiltered;
  }

  /** Returns the total number of shards. */
  public int shardCount() {
    return shardCount;
  }

  /** Returns this shard's index. */
  public int shardIndex() {
    return shardIndex;
  }
}
