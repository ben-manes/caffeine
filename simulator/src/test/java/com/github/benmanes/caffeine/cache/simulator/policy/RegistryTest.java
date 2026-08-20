/*
 * Copyright 2026 Ben Manes. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.typesafe.config.ConfigFactory;

/**
 * A capacity of zero is accepted by the configuration but not implemented by the policies, which
 * variously throw from an empty victim list, report a hit against a cache that holds nothing, or
 * spin looking for a sample they cannot draw. It is rejected once, before any of them is built.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class RegistryTest {

  @Test
  void maximumSize_zero() {
    var registry = new Registry(settings(0), Set.of());
    var error = assertThrows(IllegalArgumentException.class, registry::policies);
    assertThat(error).hasMessageThat().contains("maximum size");
  }

  @Test
  void maximumSize_positive() {
    var registry = new Registry(settings(100), Set.of());
    assertThat(registry.policies()).hasSize(1);
  }

  private static BasicSettings settings(long maximumSize) {
    var overrides = Map.of("maximum-size", maximumSize,
        "policies", List.of("linked.Lru"), "admission", List.of("Always"));
    return new BasicSettings(ConfigFactory.parseMap(overrides)
        .withFallback(ConfigFactory.load().getConfig("caffeine.simulator")));
  }
}
