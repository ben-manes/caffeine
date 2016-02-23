/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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

import java.util.concurrent.Future;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.hamcrest.core.Is;

import com.google.common.util.concurrent.Futures;

/**
 * A matcher that unwraps a future and forwards evaluation to another matcher.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsFutureValue<V> extends TypeSafeDiagnosingMatcher<Future<V>> {
  final Matcher<V> matcher;

  IsFutureValue(Matcher<V> matcher) {
    this.matcher = matcher;
  }

  @Override
  public void describeTo(Description description) {
    matcher.describeTo(description);
  }

  @Override
  protected boolean matchesSafely(Future<V> future, Description description) {
    return matcher.matches(Futures.getUnchecked(future));
  }

  public static <V> IsFutureValue<V> future(Matcher<V> matcher) {
    return new IsFutureValue<>(matcher);
  }

  public static <V> IsFutureValue<V> futureOf(V value) {
    return new IsFutureValue<>(Is.is(value));
  }
}
