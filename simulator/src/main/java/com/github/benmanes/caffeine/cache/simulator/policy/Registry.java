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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.adaptive.ArcPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.adaptive.CarPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.adaptive.CartPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.irr.ClockProPlusPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.irr.ClockProPolicy;
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
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
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
        .map(name -> factories.get(name.toLowerCase(US)))
        .filter(factory -> factory.characteristics().containsAll(characteristics))
        .flatMap(factory -> factory.creator().apply(settings.config()).stream())
        .collect(toSet());
  }

  /** Returns all of the policy variations that have been configured. */
  public Set<Policy> policy(String name) {
    Factory factory = factories.get(name.toLowerCase(US));
    checkNotNull(factory, "%s not found", name);
    return factory.creator().apply(settings.config());
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
    factories.put("opt.Clairvoyant", Factory.of(ClairvoyantPolicy.class, ClairvoyantPolicy::new));
    factories.put("opt.Unbounded", Factory.of(
        UnboundedPolicy.class, config -> new UnboundedPolicy(config, characteristics)));
  }

  private void registerLinked() {
    Stream.of(LinkedPolicy.EvictionPolicy.values()).forEach(priority -> {
      String id = "linked." + priority.name();
      factories.put(id, Factory.ofMany(LinkedPolicy.class,
          config -> LinkedPolicy.policies(config, characteristics, priority)));
    });
    Stream.of(FrequentlyUsedPolicy.EvictionPolicy.values()).forEach(priority -> {
      String id = "linked." + priority.name();
      factories.put(id, Factory.ofMany(FrequentlyUsedPolicy.class,
          config -> FrequentlyUsedPolicy.policies(config, priority)));
    });
    factories.put("linked.SegmentedLru", Factory.ofMany(
        SegmentedLruPolicy.class, SegmentedLruPolicy::policies));
    factories.put("linked.Multiqueue", Factory.of(
        MultiQueuePolicy.class, MultiQueuePolicy::new));
    factories.put("linked.S4Lru", Factory.ofMany(S4LruPolicy.class, S4LruPolicy::policies));
  }

  private void registerSampled() {
    Stream.of(SampledPolicy.EvictionPolicy.values()).forEach(priority -> {
      String id = "sampled." + priority.name();
      factories.put(id, Factory.ofMany(
          SampledPolicy.class, config -> SampledPolicy.policies(config, priority)));
    });
  }

  private void registerTwoQueue() {
    factories.put("two-queue.TuQueue", Factory.of(TuQueuePolicy.class, TuQueuePolicy::new));
    factories.put("two-queue.TwoQueue", Factory.of(TwoQueuePolicy.class, TwoQueuePolicy::new));
  }

  private void registerSketch() {
    factories.put("sketch.WindowTinyLfu", Factory.ofMany(
        WindowTinyLfuPolicy.class, WindowTinyLfuPolicy::policies));
    factories.put("sketch.S4WindowTinyLfu", Factory.ofMany(
        S4WindowTinyLfuPolicy.class, S4WindowTinyLfuPolicy::policies));
    factories.put("sketch.LruWindowTinyLfu", Factory.ofMany(
        LruWindowTinyLfuPolicy.class, LruWindowTinyLfuPolicy::policies));
    factories.put("sketch.RandomWindowtinyLfu", Factory.ofMany(
        RandomWindowTinyLfuPolicy.class, RandomWindowTinyLfuPolicy::policies));
    factories.put("sketch.FullySegmentedWindowTinylfu", Factory.ofMany(
        FullySegmentedWindowTinyLfuPolicy.class, FullySegmentedWindowTinyLfuPolicy::policies));

    factories.put("sketch.FeedbackTinyLfu", Factory.of(
        FeedbackTinyLfuPolicy.class, FeedbackTinyLfuPolicy::new));
    factories.put("sketch.FeedbackWindowTinyLfu", Factory.ofMany(
        FeedbackWindowTinyLfuPolicy.class, FeedbackWindowTinyLfuPolicy::policies));

    factories.put("sketch.HillClimberWindowTinyLfu", Factory.ofMany(
        HillClimberWindowTinyLfuPolicy.class, HillClimberWindowTinyLfuPolicy::policies));

    factories.put("sketch.TinyCache", Factory.of(TinyCachePolicy.class, TinyCachePolicy::new));
    factories.put("sketch.WindowTinyCache", Factory.of(
        WindowTinyCachePolicy.class, WindowTinyCachePolicy::new));
    factories.put("sketch.TinyCache_GhostCache", Factory.of(
        TinyCacheWithGhostCachePolicy.class, TinyCacheWithGhostCachePolicy::new));
  }

  private void registerIrr() {
    factories.put("irr.Frd", Factory.of(FrdPolicy.class, FrdPolicy::new));
    factories.put("irr.IndicatorFrd", Factory.of(
        IndicatorFrdPolicy.class, IndicatorFrdPolicy::new));
    factories.put("irr.ClimberFrd", Factory.of(
        HillClimberFrdPolicy.class, HillClimberFrdPolicy::new));

    factories.put("irr.Lirs", Factory.of(LirsPolicy.class, LirsPolicy::new));
    factories.put("irr.ClockPro", Factory.of(ClockProPolicy.class, ClockProPolicy::new));
    factories.put("irr.ClockProPlus", Factory.of(
        ClockProPlusPolicy.class, ClockProPlusPolicy::new));

    factories.put("irr.DClock", Factory.ofMany(DClockPolicy.class, DClockPolicy::policies));
  }

  private void registerAdaptive() {
    factories.put("adaptive.Arc", Factory.of(ArcPolicy.class, ArcPolicy::new));
    factories.put("adaptive.Car", Factory.of(CarPolicy.class, CarPolicy::new));
    factories.put("adaptive.Cart", Factory.of(CartPolicy.class, CartPolicy::new));
  }

  private void registerProduct() {
    factories.put("product.OHC", Factory.ofMany(OhcPolicy.class, OhcPolicy::policies));
    factories.put("product.Tcache", Factory.of(TCachePolicy.class, TCachePolicy::new));
    factories.put("product.Ehcache3", Factory.of(Ehcache3Policy.class, Ehcache3Policy::new));
    factories.put("product.Collision", Factory.ofMany(
        CollisionPolicy.class, CollisionPolicy::policies));
    factories.put("product.ExpiringMap", Factory.of(
        ExpiringMapPolicy.class, ExpiringMapPolicy::new));
    factories.put("product.Guava", Factory.of(
        GuavaPolicy.class, config -> new GuavaPolicy(config, characteristics)));
    factories.put("product.Cache2k", Factory.of(
        Cache2kPolicy.class, config -> new Cache2kPolicy(config, characteristics)));
    factories.put("product.Caffeine", Factory.of(
        CaffeinePolicy.class, config -> new CaffeinePolicy(config, characteristics)));
    factories.put("product.Elasticsearch", Factory.of(
        ElasticSearchPolicy.class, config -> new ElasticSearchPolicy(config, characteristics)));
  }

  @AutoValue
  static abstract class Factory {
    abstract Function<Config, Set<Policy>> creator();
    abstract ImmutableSet<Characteristic> characteristics();

    static Factory of(Class<? extends Policy> policyClass, Function<Config, Policy> creator) {
      return ofMany(policyClass, config -> ImmutableSet.of(creator.apply(config)));
    }
    static Factory ofMany(Class<? extends Policy> policyClass,
        Function<Config, Set<Policy>> creator) {
      PolicySpec policySpec = policyClass.getAnnotation(PolicySpec.class);
      ImmutableSet<Characteristic> characteristics = (policySpec == null)
          ? ImmutableSet.of()
          : Sets.immutableEnumSet(Arrays.asList(policySpec.characteristics()));
      return new AutoValue_Registry_Factory(creator, characteristics);
    }
  }
}
