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
package com.github.benmanes.caffeine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class UnsafeAccessTest {
  UnsafeAccess field = new UnsafeAccess(); // test coverage of constructor

  @Test
  public void load_fallback() throws Exception {
    assertThat(UnsafeAccess.load("abc", UnsafeAccess.OPEN_JDK), is(UnsafeAccess.UNSAFE));
    assertThat(UnsafeAccess.load("abc", "efg"), is(not(UnsafeAccess.UNSAFE)));
  }

  @Test
  public void objectFieldOffset() {
    assertThat(UnsafeAccess.objectFieldOffset(getClass(), "field"), is(greaterThan(0L)));
  }

  @Test(expectedExceptions = Error.class)
  public void objectFieldOffset_error() {
    UnsafeAccess.objectFieldOffset(getClass(), "foobar");
  }
}
