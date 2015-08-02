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
package com.github.benmanes.caffeine.cache.simulator.policy.victim;

import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.admission.TinyLfu;
import com.github.benmanes.caffeine.cache.simulator.policy.two_queue.TuQueuePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.victim.VictimLruPolicy.VictimSettings;
import com.typesafe.config.Config;

/**
 * An extension to the TuQueue policy to guard the COLD queue by an admission policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class VictimTuQueuePolicy extends TuQueuePolicy {

  public VictimTuQueuePolicy(String name, Config config) {
    super(name, config, admittor(config));
  }

  private static Admittor admittor(Config config) {
    VictimSettings settings = new VictimSettings(config);
    return new TinyLfu(settings.admission().eps(), settings.admission().confidence());
  }
}
