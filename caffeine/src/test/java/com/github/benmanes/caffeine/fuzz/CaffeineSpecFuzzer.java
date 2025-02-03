/*
 * Copyright 2024 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.fuzz;

import static com.google.common.truth.Truth.assertThat;

import com.code_intelligence.jazzer.junit.DictionaryEntries;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import com.github.benmanes.caffeine.cache.CaffeineSpec;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeineSpecFuzzer {

  // These tests require the environment variable JAZZER_FUZZ=1 to try new input arguments

  @FuzzTest(maxDuration = "5m")
  @DictionaryEntries({"expireAfterAccess", "expireAfterWrite", "initialCapacity", "maximumSize",
      "maximumWeight", "recordStats", "refreshAfterWrite", "softValues", "weakKeys", "weakValues"})
  public void parse(@NotNull String specification) {
    try {
      assertThat(CaffeineSpec.parse(specification)).isNotNull();
    } catch (IllegalArgumentException expected) { /* ignored */ }
  }
}
