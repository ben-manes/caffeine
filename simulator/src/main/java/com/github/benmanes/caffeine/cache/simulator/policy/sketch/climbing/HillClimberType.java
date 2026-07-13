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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing;

import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;

import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.gradient.Adam;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.gradient.AmsGrad;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.gradient.Nadam;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.gradient.Stochastic;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.hill.CorrelationClimber;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.hill.SimpleClimber;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.hill.SimulatedAnnealingClimber;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.hill.TrustRegionEwmaClimber;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.inference.IndicatorClimber;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.sim.MiniSimClimber;
import com.typesafe.config.Config;

/**
 * The hill climbers.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("ImmutableEnumChecker")
public enum HillClimberType {
  // hill climbing
  SIMPLE((_, config) -> new SimpleClimber(config)),
  CORRELATION((_, config) -> new CorrelationClimber(config)),
  TRUST_REGION_EWMA((_, config) -> new TrustRegionEwmaClimber(config)),
  SIMULATED_ANNEALING((_, config) -> new SimulatedAnnealingClimber(config)),

  // gradient descent
  STOCHASTIC_GRADIENT_DESCENT((_, config) -> new Stochastic(config)),
  AMSGRAD((_, config) -> new AmsGrad(config)),
  NADAM((_, config) -> new Nadam(config)),
  ADAM((_, config) -> new Adam(config)),

  // simulation
  MINISIM(MiniSimClimber::new),

  // inference
  INDICATOR(IndicatorClimber::new);

  private final BiFunction<Double, Config, HillClimber> factory;

  HillClimberType(BiFunction<Double, Config, HillClimber> factory) {
    this.factory = requireNonNull(factory);
  }

  public HillClimber create(double percentMain, Config config) {
    return factory.apply(percentMain, config);
  }
}
