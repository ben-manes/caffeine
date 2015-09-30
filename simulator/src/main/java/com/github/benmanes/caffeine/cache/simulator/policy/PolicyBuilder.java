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

import java.util.Locale;

import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.admission.TinyLfu;
import com.github.benmanes.caffeine.cache.simulator.policy.adaptive.ArcPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.adaptive.CarPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.adaptive.CartPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.irr.ClockProPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.irr.LirsPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.FrequentlyUsedPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.LinkedPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.SegmentedLruPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.opt.ClairvoyantPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.opt.UnboundedPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.CaffeinePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.Ehcache2Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.Ehcache3Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.GuavaPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.InfinispanPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sampled.SamplingPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.WindowTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.two_queue.TuQueuePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.two_queue.TwoQueuePolicy;
import com.typesafe.config.Config;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class PolicyBuilder {
  private final Config config;

  private Admittor admittor;
  private String type;

  public PolicyBuilder(Config config) {
    this.config = requireNonNull(config);
  }

  public PolicyBuilder type(String type) {
    this.type = requireNonNull(type);
    return this;
  }

  public PolicyBuilder admittor(String admittorType) {
    if (admittorType.equalsIgnoreCase("All")) {
      admittor = Admittor.always();
    } else if (admittorType.equalsIgnoreCase("TinyLfu")) {
      admittor = new TinyLfu(config);
    } else {
      throw new IllegalStateException("Unknown admittor: " + admittorType);
    }
    return this;
  }

  public Policy build() {
    String base = type.substring(0, type.indexOf('.'));
    String strategy = type.substring(type.lastIndexOf('.') + 1).toUpperCase(Locale.US);
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
              FrequentlyUsedPolicy.EvictionPolicy.valueOf(strategy));
        } else if (strategy.equalsIgnoreCase("SegmentedLru")) {
          return new SegmentedLruPolicy(name(), admittor, config);
        }
        return new LinkedPolicy(name(), admittor, config,
            LinkedPolicy.EvictionPolicy.valueOf(strategy));
      case "sampled":
        return new SamplingPolicy(name(), admittor, config,
            SamplingPolicy.EvictionPolicy.valueOf(strategy));
      case "two-queue":
        if (strategy.equalsIgnoreCase("TuQueue")) {
          return new TuQueuePolicy(type, config);
        } else if (strategy.equalsIgnoreCase("TwoQueue")) {
          return new TwoQueuePolicy(type, config);
        }
        break;
      case "sketch":
        if (strategy.equalsIgnoreCase("WindowTinyLfu")) {
          return new WindowTinyLfuPolicy(type, config);
        }
        break;
      case "irr":
        if (strategy.equalsIgnoreCase("Lirs")) {
          return new LirsPolicy(type, config);
        } else if (strategy.equalsIgnoreCase("ClockPro")) {
          return new ClockProPolicy(type, config);
        }
        break;
      case "adaptive":
        if (strategy.equalsIgnoreCase("Arc")) {
          return new ArcPolicy(type, config);
        } else if (strategy.equalsIgnoreCase("Car")) {
          return new CarPolicy(type, config);
        } else if (strategy.equalsIgnoreCase("Cart")) {
          return new CartPolicy(type, config);
        }
        break;
      case "product":
        if (strategy.equalsIgnoreCase("Guava")) {
          return new GuavaPolicy(type, config);
        } else if (strategy.equalsIgnoreCase("Ehcache2")) {
          return new Ehcache2Policy(type, config);
        } else if (strategy.equalsIgnoreCase("Ehcache3")) {
          return new Ehcache3Policy(type, config);
        } else if (strategy.equalsIgnoreCase("Caffeine")) {
          return new CaffeinePolicy(type, config);
        } else if (strategy.equalsIgnoreCase("Infinispan")) {
          return new InfinispanPolicy(type, config);
        }
        break;
      default:
        break;
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
