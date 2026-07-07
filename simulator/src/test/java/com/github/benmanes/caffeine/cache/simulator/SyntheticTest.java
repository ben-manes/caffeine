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
package com.github.benmanes.caffeine.cache.simulator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class SyntheticTest {

  @Test
  void zipfian_constant() {
    assertThrows(IllegalArgumentException.class, () -> Synthetic.zipfian(5000, 1.0, 100));
    assertThrows(IllegalArgumentException.class,
        () -> Synthetic.scrambledZipfian(5000, 1.0, 100));
    assertDoesNotThrow(() -> Synthetic.zipfian(5000, 0.99, 100));
    assertDoesNotThrow(() -> Synthetic.scrambledZipfian(5000, 0.99, 100));
  }
}
