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
package com.github.benmanes.caffeine.cache.testing;

import static java.util.Locale.US;
import static org.apache.commons.lang3.StringUtils.capitalize;

import java.util.Optional;

import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Stats;
import com.google.common.base.Enums;

/**
 * The test generation options.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class Options {

  private Options() {}

  /** Returns the test options from system property configuration. */
  public static Options fromSystemProperties() {
    return new Options();
  }

  /** Compute indicates if an async or sync cache variation should be use, or both if unset. */
  Optional<Compute> compute() {
    return Optional.ofNullable(Enums.getIfPresent(Compute.class,
        System.getProperty("compute", "").toUpperCase(US)).orNull());
  }

  /** Implementation variation to use, or all if unset. */
  Optional<Implementation> implementation() {
    return Optional.ofNullable(Enums.getIfPresent(Implementation.class,
        capitalize(System.getProperty("implementation", "").toLowerCase(US))).orNull());
  }

  /** Indicates if statistics should be used, both if unset */
  Optional<Stats> stats() {
    return Optional.ofNullable(Enums.getIfPresent(Stats.class,
        System.getProperty("stats", "").toUpperCase(US)).orNull());
  }

  /** The key reference combination to use, or all if unset. */
  Optional<ReferenceType> keys() {
    return Optional.ofNullable(Enums.getIfPresent(ReferenceType.class,
        System.getProperty("keys", "").toUpperCase(US)).orNull());
  }

  /** The value reference combination to use, or all if unset. */
  Optional<ReferenceType> values() {
    return Optional.ofNullable(Enums.getIfPresent(ReferenceType.class,
        System.getProperty("values", "").toUpperCase(US)).orNull());
  }
}
