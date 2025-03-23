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
package com.github.benmanes.caffeine.cache.simulator.admission;

import static java.util.Locale.US;

import java.util.function.BiFunction;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.clairvoyant.Clairvoyant;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.Enums;
import com.typesafe.config.Config;

/**
 * The admission policies.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("ImmutableEnumChecker")
public enum Admission {
  ALWAYS((_, _) -> Admittor.always(), ""),
  CLAIRVOYANT(Clairvoyant::new, "_Clairvoyant"),
  TINYLFU(TinyLfu::new, "_TinyLfu");

  private final BiFunction<Config, PolicyStats, Admittor> factory;
  private final String suffix;

  Admission(BiFunction<Config, PolicyStats, Admittor> factory, String suffix) {
    this.factory = factory;
    this.suffix = suffix;
  }

  /**
   * Returns a configured admittor.
   *
   * @param config the configuration
   * @param policyStats the stats
   * @return an admission policy
   */
  public Admittor from(Config config, PolicyStats policyStats) {
    if (this == TINYLFU) {
      var override = new BasicSettings(config).tinyLfu().sketch().toUpperCase(US);
      return Enums.getIfPresent(Admission.class, override).or(this)
          .factory.apply(config, policyStats);
    }
    return factory.apply(config, policyStats);
  }

  /** Returns the policy's formatted name. */
  public String format(String name) {
    return name + suffix;
  }
}
