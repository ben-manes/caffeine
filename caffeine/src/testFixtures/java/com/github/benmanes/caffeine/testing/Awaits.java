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
package com.github.benmanes.caffeine.testing;

import java.time.Duration;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Awaits {
  private static final Duration ONE_MILLISECOND = Duration.ofMillis(1);

  private Awaits() {}

  /** Returns a configured {@link ConditionFactory} that polls at a short interval. */
  public static ConditionFactory await() {
    return Awaitility.with()
        .pollDelay(ONE_MILLISECOND)
        .pollInterval(ONE_MILLISECOND)
        .pollExecutorService(ConcurrentTestHarness.executor);
  }
}
