/*
 * Copyright 2021 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.lincheck;

import java.time.Duration;

import org.testng.annotations.Factory;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Linearization test cases.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Test(groups = "lincheck")
public final class CaffeineLincheckTest {

  @Factory
  public Object[] factory() {
    return new Object[] { new BoundedLincheckTest(), new UnboundedLincheckTest() };
  }

  public static final class BoundedLincheckTest extends AbstractLincheckCacheTest {
    public BoundedLincheckTest() {
      super(Caffeine.newBuilder()
          .maximumSize(Long.MAX_VALUE)
          .expireAfterWrite(Duration.ofNanos(Long.MAX_VALUE)));
    }
  }

  public static final class UnboundedLincheckTest extends AbstractLincheckCacheTest {
    public UnboundedLincheckTest() {
      super(Caffeine.newBuilder());
    }
  }
}
