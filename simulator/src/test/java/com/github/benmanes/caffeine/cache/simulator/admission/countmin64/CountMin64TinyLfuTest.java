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
package com.github.benmanes.caffeine.cache.simulator.admission.countmin64;

import static com.google.common.truth.Truth.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class CountMin64TinyLfuTest {

  @Test
  void reset_sizeNotNegative() {
    var tinyLfu = new CountMin64TinyLfu(config(100));
    int sampleSize = tinyLfu.sampleSize;
    for (int i = 0; i <= sampleSize; i++) {
      tinyLfu.increment(i);
    }
    assertThat(tinyLfu.size).isAtLeast(0);
  }

  private static Config config(int maximumSize) {
    var properties = Map.<String, Object>of("maximum-size", maximumSize);
    return ConfigFactory.parseMap(properties)
        .withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
  }
}
