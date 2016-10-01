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
package com.github.benmanes.caffeine.cache.simulator.policy.product;

import static java.util.stream.Collectors.toList;

import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.text.WordUtils;

import com.fabahaba.collision.cache.CollisionBuilder;
import com.fabahaba.collision.cache.CollisionCache;
import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.collect.ImmutableSet;
import com.typesafe.config.Config;

/**
 * Collision cache implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CollisionPolicy implements Policy {
  private final CollisionCache<Object, Object> cache;
  private final PolicyStats policyStats;
  private final int maximumSize;

  private int trackedSize;

  public CollisionPolicy(CollisionSettings settings, Density density) {
    policyStats = new PolicyStats(String.format("product.Collision (%s)",
        WordUtils.capitalize(density.name().toLowerCase())));
    maximumSize = settings.maximumSize();

    CollisionBuilder<Object> builder = CollisionCache
        .withCapacity(maximumSize)
        .setInitCount(settings.initCount())
        .setBucketSize(settings.bucketSize());

    if (density == Density.SPARSE) {
      cache = builder.buildSparse();
    } else if (density == Density.PACKED) {
      cache = builder.buildPacked();
    } else {
      throw new IllegalArgumentException();
    }
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    CollisionSettings settings = new CollisionSettings(config);
    return ImmutableSet.copyOf(settings.density().stream()
        .map(density -> new CollisionPolicy(settings, density))
        .collect(toList()));
  }

  @Override
  public void record(long key) {
    Object value = cache.getIfPresent(key);
    if (value == null) {
      if (trackedSize == maximumSize) {
        policyStats.recordEviction();
        trackedSize--;
      }
      cache.putIfAbsent(key, key);
      policyStats.recordMiss();
      trackedSize++;
    } else {
      policyStats.recordHit();
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  enum Density { SPARSE, PACKED }

  static final class CollisionSettings extends BasicSettings {
    public CollisionSettings(Config config) {
      super(config);
    }
    public int initCount() {
      return config().getInt("collision.init-count");
    }
    public int bucketSize() {
      return config().getInt("collision.bucket-size");
    }
    public List<Density> density() {
      return config().getStringList("collision.density").stream()
          .map(denity -> Density.valueOf(denity.toUpperCase()))
          .collect(toList());
    }
  }
}
