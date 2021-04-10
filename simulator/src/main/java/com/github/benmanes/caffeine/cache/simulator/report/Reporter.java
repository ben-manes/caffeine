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
package com.github.benmanes.caffeine.cache.simulator.report;

import java.io.IOException;
import java.util.Collection;

import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;

/**
 * A reporter that collects the results and prints the output.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public interface Reporter {

  /** Adds the result of a policy simulation. */
  void add(PolicyStats policyStats);

  /** Writes the report to the output destination. */
  void print() throws IOException;

  /** Returns the collected statistics. */
  Collection<PolicyStats> stats();
}
