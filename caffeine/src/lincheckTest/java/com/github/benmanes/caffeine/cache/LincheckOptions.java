/*
 * Copyright 2025 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache;

/**
 * The Lincheck test options. The default configuration allows for quick sanity testing and can be
 * overridden using system properties,
 * <p>
 * <code>lincheck.[modelChecking, stress].[iterations, invocationsPerIteration]</code>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class LincheckOptions {
  /** The number of different scenarios. */
  public final int iterations;
  /** How deeply each scenario is tested. */
  public final int invocationsPerIteration;

  private LincheckOptions(int iterations, int invocationsPerIteration) {
    this.invocationsPerIteration = invocationsPerIteration;
    this.iterations = iterations;
  }

  /**
   * For checking linearizability with bounded model checking. Unlike stress testing, this
   * approach can also provide a trace of an incorrect execution. However, it uses sequential
   * consistency model, so it cannot find any low-level bugs (e.g., missing 'volatile'), and thus,
   * it is recommended to have both test modes.
   */
  public static LincheckOptions modelChecking() {
    return new LincheckOptions(
        Integer.getInteger(propertyName("modelChecking", "iterations"), 25),
        Integer.getInteger(propertyName("modelChecking", "invocationsPerIteration"), 400));
  }

  /** For checking linearizability with stress testing. */
  public static LincheckOptions stress() {
    return new LincheckOptions(
        Integer.getInteger(propertyName("stress", "iterations"), 30),
        Integer.getInteger(propertyName("stress", "invocationsPerIteration"), 3_000));
  }

  private static String propertyName(String strategy, String option) {
    return "lincheck." + strategy + "." + option;
  }
}
