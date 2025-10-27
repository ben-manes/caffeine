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

import static java.util.Locale.US;
import static org.apache.commons.lang3.StringUtils.capitalize;

import java.util.Optional;
import java.util.Properties;

import com.github.benmanes.caffeine.cache.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.CacheSpec.Stats;
import com.google.common.base.Enums;

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

  private Options(Properties properties) {
    implementation = Optional.ofNullable(Enums.getIfPresent(Implementation.class,
        capitalize(properties.getProperty("implementation", "").toLowerCase(US))).orNull());
    compute = Optional.ofNullable(Enums.getIfPresent(Compute.class,
        properties.getProperty("compute", "").toUpperCase(US)).orNull());
    keys = Optional.ofNullable(Enums.getIfPresent(ReferenceType.class,
        properties.getProperty("keys", "").toUpperCase(US)).orNull());
    values = Optional.ofNullable(Enums.getIfPresent(ReferenceType.class,
        properties.getProperty("values", "").toUpperCase(US)).orNull());
    stats = Optional.ofNullable(Enums.getIfPresent(Stats.class,
        properties.getProperty("stats", "").toUpperCase(US)).orNull());
  }

  /** Returns the test options from the system properties. */
  public static Options fromSystemProperties() {
    return SYSTEM_PROPERTY_OPTIONS;
  }

  /** The implementation, or all if unset. */
  Optional<Implementation> implementation() {
    return implementation;
  }

  /** The reference type, or all if unset. */
  Optional<ReferenceType> keys() {
    return keys;
  }

  /** The reference type, or all if unset. */
  Optional<ReferenceType> values() {
    return values;
  }

  /** The computation type, or all if unset. */
  Optional<Compute> compute() {
    return compute;
  }

  /** The statistics type, or all if unset. */
  Optional<Stats> stats() {
    return stats;
  }
}
