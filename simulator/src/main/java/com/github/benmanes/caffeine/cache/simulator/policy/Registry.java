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

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Locale.US;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.github.benmanes.caffeine.cache.simulator.policy.adaptive.ArcPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.adaptive.CarPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.adaptive.CartPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.irr.ClockProPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.irr.ClockProPlusPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.irr.DClockPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.irr.FrdPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.irr.HillClimberFrdPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.irr.IndicatorFrdPolicy;
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
import com.github.benmanes.caffeine.cache.simulator.policy.product.Ehcache3Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.ElasticSearchPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.ExpiringMapPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.GuavaPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.OhcPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.TCachePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sampled.SampledPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.WindowTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimberWindowTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.feedback.FeedbackTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.feedback.FeedbackWindowTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.segment.FullySegmentedWindowTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.segment.LruWindowTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.segment.RandomWindowTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.segment.S4WindowTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.tinycache.TinyCachePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.tinycache.TinyCacheWithGhostCachePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.tinycache.WindowTinyCachePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.two_queue.TuQueuePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.two_queue.TwoQueuePolicy;
import com.typesafe.config.Config;

/**
 * The registry of caching policies.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Registry {
  private final Set<Characteristic> characteristics;
  private final Map<String, Factory> factories;
  private final BasicSettings settings;

  public Registry(BasicSettings settings, Set<Characteristic> characteristics) {
    this.characteristics = characteristics;
    this.factories = new HashMap<>();
    this.settings = settings;
    buildRegistry();
  }

  /**
   * Returns all of the policies that have been configured for simulation and that meet a minimal
   * set of supported characteristics.
   */
  public Set<Policy> policies() {
    return settings.policies().stream()
        .flatMap(name -> policy(name).stream())
        .filter(policy -> policy.characteristics().containsAll(characteristics))
        .collect(toSet());
  }

  /** Returns all of the policy variations that have been configured. */
  public Set<Policy> policy(String name) {
    Factory factory = factories.get(name.toLowerCase(US));
    checkNotNull(factory, "%s not found", name);
    return factory.apply(settings.config());
  }

  private void buildRegistry() {
    registerIrr();
    registerLinked();
    registerSketch();
    registerOptimal();
    registerSampled();
    registerProduct();
    registerTwoQueue();
    registerAdaptive();

    Map<String, Factory> normalized = factories.entrySet().stream()
        .collect(toMap(entry -> entry.getKey().toLowerCase(US), Map.Entry::getValue));
    factories.clear();
    factories.putAll(normalized);
  }

  private void registerOptimal() {
    factories.put("opt.Clairvoyant", ClairvoyantPolicy::policies);
    factories.put("opt.Unbounded", UnboundedPolicy::policies);
  }

  private void registerLinked() {
    Stream.of(LinkedPolicy.EvictionPolicy.values()).forEach(priority -> {
      String id = "linked." + priority.name();
      factories.put(id, config -> LinkedPolicy.policies(config, characteristics, priority));
    });
    Stream.of(FrequentlyUsedPolicy.EvictionPolicy.values()).forEach(priority -> {
      String id = "linked." + priority.name();
      factories.put(id, config -> FrequentlyUsedPolicy.policies(config, priority));
    });
    factories.put("linked.SegmentedLru", SegmentedLruPolicy::policies);
    factories.put("linked.Multiqueue", MultiQueuePolicy::policies);
    factories.put("linked.S4Lru", S4LruPolicy::policies);
  }

  private void registerSampled() {
    Stream.of(SampledPolicy.EvictionPolicy.values()).forEach(priority -> {
      String id = "sampled." + priority.name();
      factories.put(id, config -> SampledPolicy.policies(config, priority));
    });
  }

  private void registerTwoQueue() {
    factories.put("two-queue.TuQueue", TuQueuePolicy::policies);
    factories.put("two-queue.TwoQueue", TwoQueuePolicy::policies);
  }

  private void registerSketch() {
    factories.put("sketch.WindowTinyLfu", WindowTinyLfuPolicy::policies);
    factories.put("sketch.S4WindowTinyLfu", S4WindowTinyLfuPolicy::policies);
    factories.put("sketch.LruWindowTinyLfu", LruWindowTinyLfuPolicy::policies);
    factories.put("sketch.RandomWindowtinyLfu", RandomWindowTinyLfuPolicy::policies);
    factories.put("sketch.FullySegmentedWindowTinylfu",
        FullySegmentedWindowTinyLfuPolicy::policies);

    factories.put("sketch.FeedbackTinyLfu", FeedbackTinyLfuPolicy::policies);
    factories.put("sketch.FeedbackWindowTinyLfu", FeedbackWindowTinyLfuPolicy::policies);

    factories.put("sketch.HillClimberWindowTinyLfu", HillClimberWindowTinyLfuPolicy::policies);

    factories.put("sketch.TinyCache", TinyCachePolicy::policies);
    factories.put("sketch.WindowTinyCache", WindowTinyCachePolicy::policies);
    factories.put("sketch.TinyCache_GhostCache", TinyCacheWithGhostCachePolicy::policies);
  }

  private void registerIrr() {
    factories.put("irr.Frd", FrdPolicy::policies);
    factories.put("irr.IndicatorFrd", IndicatorFrdPolicy::policies);
    factories.put("irr.ClimberFrd", HillClimberFrdPolicy::policies);

    factories.put("irr.Lirs", LirsPolicy::policies);
    factories.put("irr.ClockPro", ClockProPolicy::policies);
    factories.put("irr.ClockProPlus", ClockProPlusPolicy::policies);

    factories.put("irr.DClock", DClockPolicy::policies);
  }

  private void registerAdaptive() {
    factories.put("adaptive.Arc", ArcPolicy::policies);
    factories.put("adaptive.Car", CarPolicy::policies);
    factories.put("adaptive.Cart", CartPolicy::policies);
  }

  private void registerProduct() {
    factories.put("product.OHC", OhcPolicy::policies);
    factories.put("product.Tcache", TCachePolicy::policies);
    factories.put("product.Ehcache3", Ehcache3Policy::policies);
    factories.put("product.Collision", CollisionPolicy::policies);
    factories.put("product.ExpiringMap", ExpiringMapPolicy::policies);
    factories.put("product.Guava", config -> GuavaPolicy.policies(config, characteristics));
    factories.put("product.Cache2k", config -> Cache2kPolicy.policies(config, characteristics));
    factories.put("product.Caffeine", config -> CaffeinePolicy.policies(config, characteristics));
    factories.put("product.Elasticsearch", config ->
        ElasticSearchPolicy.policies(config, characteristics));
  }

  private interface Factory extends Function<Config, Set<Policy>> {}
}
