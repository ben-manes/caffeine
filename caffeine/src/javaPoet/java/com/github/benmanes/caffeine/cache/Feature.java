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

import static java.util.stream.Collectors.joining;

import java.util.Collections;
import java.util.Set;

import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;

/**
 * The features that may be code generated.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public enum Feature {
  STRONG_KEYS,
  WEAK_KEYS,

  STRONG_VALUES,
  INFIRM_VALUES,
  WEAK_VALUES,
  SOFT_VALUES,

  EXPIRE_ACCESS,
  EXPIRE_WRITE,
  REFRESH_WRITE,

  MAXIMUM_SIZE,
  MAXIMUM_WEIGHT,

  LISTENING,
  STATS;

  private static final ImmutableSet<Feature> fastPathIncompatible = Sets.immutableEnumSet(
      Feature.EXPIRE_ACCESS, Feature.WEAK_KEYS, Feature.INFIRM_VALUES,
      Feature.WEAK_VALUES, Feature.SOFT_VALUES);

  public static String makeEnumName(String enumName) {
    return CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, enumName);
  }

  public static String makeClassName(Iterable<Feature> features) {
    String enumName = Streams.stream(features)
        .map(Feature::name)
        .collect(joining("_"));
    return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, enumName);
  }

  public static boolean usesWriteOrderDeque(Set<Feature> features) {
    return features.contains(Feature.EXPIRE_WRITE);
  }

  public static boolean usesAccessOrderWindowDeque(Set<Feature> features) {
    return features.contains(Feature.MAXIMUM_SIZE)
        || features.contains(Feature.MAXIMUM_WEIGHT)
        || features.contains(Feature.EXPIRE_ACCESS);
  }

  public static boolean usesAccessOrderMainDeque(Set<Feature> features) {
    return features.contains(Feature.MAXIMUM_SIZE)
        || features.contains(Feature.MAXIMUM_WEIGHT);
  }

  public static boolean useWriteTime(Set<Feature> features) {
    return features.contains(Feature.EXPIRE_WRITE)
        || features.contains(Feature.REFRESH_WRITE);
  }

  public static boolean usesExpirationTicker(Set<Feature> features) {
    return features.contains(Feature.EXPIRE_ACCESS)
        || features.contains(Feature.EXPIRE_WRITE)
        || features.contains(Feature.REFRESH_WRITE);
  }

  public static boolean usesExpiration(Set<Feature> features) {
    return features.contains(Feature.EXPIRE_ACCESS)
        || features.contains(Feature.EXPIRE_WRITE);
  }

  public static boolean usesMaximum(Set<Feature> features) {
    return features.contains(Feature.MAXIMUM_SIZE)
        || features.contains(Feature.MAXIMUM_WEIGHT);
  }

  public static boolean usesFastPath(Set<Feature> features) {
    return Collections.disjoint(features, fastPathIncompatible) && usesMaximum(features);
  }
}
