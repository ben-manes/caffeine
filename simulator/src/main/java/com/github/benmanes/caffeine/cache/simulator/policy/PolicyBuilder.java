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

import static java.util.Objects.requireNonNull;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.admission.TinyLfu;
import com.github.benmanes.caffeine.cache.simulator.policy.adaptive.ArcPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.irr.JackrabbitLirsPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.FrequentlyUsedPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.LinkedPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.SegmentedLruPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.opt.ClairvoyantPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.opt.UnboundedPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sampled.SamplingPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.two_queue.TwoQueuePolicy;
import com.typesafe.config.Config;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class PolicyBuilder {
  private Admittor admittor;
  private Config config;
  private String type;

  public PolicyBuilder(Config config) {
    this.config = requireNonNull(config);
  }

  public PolicyBuilder type(String type) {
    this.type = requireNonNull(type);
    return this;
  }

  public PolicyBuilder admittor(String admittorType) {
    if (admittorType.equals("None")) {
      admittor = Admittor.always();
    } else if (admittorType.equals("TinyLfu")) {
      BasicSettings settings = new BasicSettings(config);
      admittor = new TinyLfu(settings.admission().eps(), settings.admission().confidence());
    } else {
      throw new IllegalStateException("Unknown admittor: " + admittorType);
    }
    return this;
  }

  public Policy build() {
    String base = type.substring(0, type.indexOf('.'));
    String strategy = type.substring(type.lastIndexOf('.') + 1);
    switch (base) {
      case "opt":
        if (strategy.equalsIgnoreCase("Clairvoyant")) {
          return new ClairvoyantPolicy(type, config);
        } else if (strategy.equalsIgnoreCase("Unbounded")) {
          return new UnboundedPolicy(type);
        }
        break;
      case "linked":
        if (strategy.equalsIgnoreCase("Lfu") || strategy.equalsIgnoreCase("Mfu")) {
          return new FrequentlyUsedPolicy(name(), admittor, config,
              FrequentlyUsedPolicy.EvictionPolicy.valueOf(strategy.toUpperCase()));
        } else if (strategy.equalsIgnoreCase("SegmentedLru")) {
          return new SegmentedLruPolicy(name(), admittor, config);
        }
        return new LinkedPolicy(name(), admittor, config,
            LinkedPolicy.EvictionPolicy.valueOf(strategy.toUpperCase()));
      case "sampled":
        return new SamplingPolicy(name(), admittor, config,
            SamplingPolicy.EvictionPolicy.valueOf(strategy.toUpperCase()));
      case "two-queue":
        return new TwoQueuePolicy(type, config);
      case "irr":
        return new JackrabbitLirsPolicy(type, config);
      case "adaptive":
        return new ArcPolicy(type, config);
    }
    throw new IllegalStateException("Unknown policy: " + type);
  }

  private String name() {
    if (admittor == Admittor.always()) {
      return type;
    }
    return type + "_" + admittor.getClass().getSimpleName();
  }
}
