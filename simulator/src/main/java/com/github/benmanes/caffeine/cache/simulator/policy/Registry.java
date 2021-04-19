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
import static com.google.common.base.Preconditions.checkState;
import static java.util.Locale.US;
import static java.util.stream.Collectors.toSet;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.adaptive.ArcPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.adaptive.CarPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.adaptive.CartPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.greedy_dual.GDWheelPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.greedy_dual.GdsfPolicy;
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
    registerGreedyDual();
  }

  /** Registers the policy based on the annotated name. */
  private void register(Class<? extends Policy> policyClass, Function<Config, Policy> creator) {
    registerMany(policyClass, config -> ImmutableSet.of(creator.apply(config)));
  }

  /** Registers the policy based on the annotated name. */
  private void register(Class<? extends Policy> policyClass,
      BiFunction<Config, Set<Characteristic>, Policy> creator) {
    registerMany(policyClass, config -> ImmutableSet.of(creator.apply(config, characteristics)));
  }

  /** Registers the policy based on the annotated name. */
  private void registerMany(Class<? extends Policy> policyClass,
      Function<Config, Set<Policy>> creator) {
    PolicySpec policySpec = policyClass.getAnnotation(PolicySpec.class);
    checkState(isNotBlank(policySpec.name()), "The name must be specified on %s", policyClass);
    registerMany(policySpec.name(), policyClass, creator);
  }

  /** Registers the policy using the specified name. */
  private void registerMany(String name, Class<? extends Policy> policyClass,
      Function<Config, Set<Policy>> creator) {
    factories.put(name.trim().toLowerCase(US), Factory.of(policyClass, creator));
  }

  private void registerOptimal() {
    register(ClairvoyantPolicy.class, ClairvoyantPolicy::new);
    register(UnboundedPolicy.class, config -> new UnboundedPolicy(config, characteristics));
  }

  private void registerLinked() {
    Stream.of(LinkedPolicy.EvictionPolicy.values()).forEach(priority -> {
      registerMany(priority.label(), LinkedPolicy.class,
          config -> LinkedPolicy.policies(config, characteristics, priority));
    });
    Stream.of(FrequentlyUsedPolicy.EvictionPolicy.values()).forEach(priority -> {
      registerMany(priority.label(), FrequentlyUsedPolicy.class,
          config -> FrequentlyUsedPolicy.policies(config, priority));
    });
    registerMany(S4LruPolicy.class, S4LruPolicy::policies);
    register(MultiQueuePolicy.class, MultiQueuePolicy::new);
    registerMany(SegmentedLruPolicy.class, SegmentedLruPolicy::policies);
  }

  private void registerSampled() {
    Stream.of(SampledPolicy.EvictionPolicy.values()).forEach(priority -> {
      registerMany(priority.label(), SampledPolicy.class,
          config -> SampledPolicy.policies(config, priority));
    });
  }

  private void registerTwoQueue() {
    register(TuQueuePolicy.class, TuQueuePolicy::new);
    register(TwoQueuePolicy.class, TwoQueuePolicy::new);
  }

  private void registerSketch() {
    registerMany(WindowTinyLfuPolicy.class, WindowTinyLfuPolicy::policies);
    registerMany(S4WindowTinyLfuPolicy.class, S4WindowTinyLfuPolicy::policies);
    registerMany(LruWindowTinyLfuPolicy.class, LruWindowTinyLfuPolicy::policies);
    registerMany(RandomWindowTinyLfuPolicy.class, RandomWindowTinyLfuPolicy::policies);
    registerMany(FullySegmentedWindowTinyLfuPolicy.class,
        FullySegmentedWindowTinyLfuPolicy::policies);

    register(FeedbackTinyLfuPolicy.class, FeedbackTinyLfuPolicy::new);
    registerMany(FeedbackWindowTinyLfuPolicy.class, FeedbackWindowTinyLfuPolicy::policies);

    registerMany(HillClimberWindowTinyLfuPolicy.class, HillClimberWindowTinyLfuPolicy::policies);

    register(TinyCachePolicy.class, TinyCachePolicy::new);
    register(WindowTinyCachePolicy.class, WindowTinyCachePolicy::new);
    register(TinyCacheWithGhostCachePolicy.class, TinyCacheWithGhostCachePolicy::new);
  }

  private void registerIrr() {
    register(FrdPolicy.class, FrdPolicy::new);
    register(IndicatorFrdPolicy.class, IndicatorFrdPolicy::new);
    register(HillClimberFrdPolicy.class, HillClimberFrdPolicy::new);

    register(LirsPolicy.class, LirsPolicy::new);
    register(ClockProPolicy.class, ClockProPolicy::new);
    register(ClockProPlusPolicy.class, ClockProPlusPolicy::new);

    registerMany(DClockPolicy.class, DClockPolicy::policies);
  }

  private void registerAdaptive() {
    register(ArcPolicy.class, ArcPolicy::new);
    register(CarPolicy.class, CarPolicy::new);
    register(CartPolicy.class, CartPolicy::new);
  }

  private void registerGreedyDual() {
    register(GdsfPolicy.class, GdsfPolicy::new);
    register(GDWheelPolicy.class, GDWheelPolicy::new);
  }

  private void registerProduct() {
    register(GuavaPolicy.class, GuavaPolicy::new);
    register(TCachePolicy.class, TCachePolicy::new);
    register(Cache2kPolicy.class, Cache2kPolicy::new);
    registerMany(OhcPolicy.class, OhcPolicy::policies);
    register(CaffeinePolicy.class, CaffeinePolicy::new);
    register(Ehcache3Policy.class, Ehcache3Policy::new);
    register(ExpiringMapPolicy.class, ExpiringMapPolicy::new);
    register(ElasticSearchPolicy.class, ElasticSearchPolicy::new);
  }

  @AutoValue
  static abstract class Factory {
    abstract Class<? extends Policy> policyClass();
    abstract Function<Config, Set<Policy>> creator();

    Set<Characteristic> characteristics() {
      PolicySpec policySpec = policyClass().getAnnotation(PolicySpec.class);
      return (policySpec == null)
          ? ImmutableSet.of()
          : ImmutableSet.copyOf(policySpec.characteristics());
    }

    static Factory of(Class<? extends Policy> policyClass, Function<Config, Set<Policy>> creator) {
      return new AutoValue_Registry_Factory(policyClass, creator);
    }
  }
}
