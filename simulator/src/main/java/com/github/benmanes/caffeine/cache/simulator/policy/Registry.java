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
package com.github.benmanes.caffeine.cache.simulator.policy;

import static java.util.stream.Collectors.toSet;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.adaptive.ArcPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.adaptive.CarPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.adaptive.CartPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.irr.ClockProPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.irr.LirsPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.FrequentlyUsedPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.LinkedPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.MultiQueuePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.S4LruPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.SegmentedLruPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.opt.ClairvoyantPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.opt.UnboundedPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.Cache2kPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.CaffeinePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.CollisionPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.Ehcache2Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.Ehcache3Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.ElasticSearchPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.GuavaPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.InfinispanPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.OhcPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.TCachePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sampled.SamplingPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.AdaptiveTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.AdaptiveWindowTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.FullySegmentedWindowTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.RandomWindowTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.S4WindowTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.SimpleWindowTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.WindowTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.tinycache.TinyCachePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.tinycache.TinyCacheWithGhostCachePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.tinycache.WindowTinyCachePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.two_queue.TuQueuePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.two_queue.TwoQueuePolicy;
import com.google.common.collect.ImmutableMap;
import com.typesafe.config.Config;

/**
 * The registry of caching policies.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Registry {
  private static final Map<String, Function<Config, Set<Policy>>> FACTORIES = makeRegistry();

  private Registry() {}

  private static Map<String, Function<Config, Set<Policy>>> makeRegistry() {
    Map<String, Function<Config, Set<Policy>>> factories = new HashMap<>();
    registerIRR(factories);
    registerLinked(factories);
    registerSketch(factories);
    registerOptimal(factories);
    registerSampled(factories);
    registerProduct(factories);
    registerTwoQueue(factories);
    registerAdaptive(factories);
    return ImmutableMap.copyOf(factories);
  }

  /** Returns all of the policies that have been configured for simulation. */
  public static Set<Policy> policies(BasicSettings settings) {
    return settings.policies().stream()
        .map(FACTORIES::get)
        .flatMap(factory -> factory.apply(settings.config()).stream())
        .collect(toSet());
  }

  private static void registerOptimal(Map<String, Function<Config, Set<Policy>>> factories) {
    factories.put("opt.clairvoyant", ClairvoyantPolicy::policies);
    factories.put("opt.unbounded", UnboundedPolicy::policies);
  }

  private static void registerLinked(Map<String, Function<Config, Set<Policy>>> factories) {
    Stream.of(LinkedPolicy.EvictionPolicy.values()).forEach(priority -> {
      String id = "linked." + priority.name().toLowerCase();
      factories.put(id, config -> LinkedPolicy.policies(config, priority));
    });
    Stream.of(FrequentlyUsedPolicy.EvictionPolicy.values()).forEach(priority -> {
      String id = "linked." + priority.name().toLowerCase();
      factories.put(id, config -> FrequentlyUsedPolicy.policies(config, priority));
    });
    factories.put("linked.segmentedlru", SegmentedLruPolicy::policies);
    factories.put("linked.multiqueue", MultiQueuePolicy::policies);
    factories.put("linked.s4lru", S4LruPolicy::policies);
  }

  private static void registerSampled(Map<String, Function<Config, Set<Policy>>> factories) {
    Stream.of(SamplingPolicy.EvictionPolicy.values()).forEach(priority -> {
      String id = "sampled." + priority.name().toLowerCase();
      factories.put(id, config -> SamplingPolicy.policies(config, priority));
    });
  }

  private static void registerTwoQueue(Map<String, Function<Config, Set<Policy>>> factories) {
    factories.put("two-queue.tuqueue", TuQueuePolicy::policies);
    factories.put("two-queue.twoqueue", TwoQueuePolicy::policies);
  }

  private static void registerSketch(Map<String, Function<Config, Set<Policy>>> factories) {
    factories.put("sketch.windowtinylfu", WindowTinyLfuPolicy::policies);
    factories.put("sketch.s4windowtinylfu", S4WindowTinyLfuPolicy::policies);
    factories.put("sketch.simplewindowtinylfu", SimpleWindowTinyLfuPolicy::policies);
    factories.put("sketch.randomwindowtinylfu", RandomWindowTinyLfuPolicy::policies);
    factories.put("sketch.fullysegmentedwindowtinylfu",
        FullySegmentedWindowTinyLfuPolicy::policies);
    factories.put("sketch.adaptivetinylfu", AdaptiveTinyLfuPolicy::policies);
    factories.put("sketch.adaptivewindowtinylfu", AdaptiveWindowTinyLfuPolicy::policies);

    factories.put("sketch.tinycache", TinyCachePolicy::policies);
    factories.put("sketch.windowtinycache", WindowTinyCachePolicy::policies);
    factories.put("sketch.tinycache_ghostcache", TinyCacheWithGhostCachePolicy::policies);
  }

  private static void registerIRR(Map<String, Function<Config, Set<Policy>>> factories) {
    factories.put("irr.lirs", LirsPolicy::policies);
    factories.put("irr.clockpro", ClockProPolicy::policies);
  }

  private static void registerAdaptive(Map<String, Function<Config, Set<Policy>>> factories) {
    factories.put("adaptive.arc", ArcPolicy::policies);
    factories.put("adaptive.car", CarPolicy::policies);
    factories.put("adaptive.cart", CartPolicy::policies);
  }

  private static void registerProduct(Map<String, Function<Config, Set<Policy>>> factories) {
    factories.put("product.ohc", OhcPolicy::policies);
    factories.put("product.guava", GuavaPolicy::policies);
    factories.put("product.tcache", TCachePolicy::policies);
    factories.put("product.cache2k", Cache2kPolicy::policies);
    factories.put("product.ehcache2", Ehcache2Policy::policies);
    factories.put("product.ehcache3", Ehcache3Policy::policies);
    factories.put("product.caffeine", CaffeinePolicy::policies);
    factories.put("product.collision", CollisionPolicy::policies);
    factories.put("product.infinispan", InfinispanPolicy::policies);
    factories.put("product.elasticsearch", ElasticSearchPolicy::policies);
  }
}
