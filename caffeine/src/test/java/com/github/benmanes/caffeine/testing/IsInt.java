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
package com.github.benmanes.caffeine.testing;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.hamcrest.core.Is;

/**
 * A matcher that compares an {@link Int} to an {@link Integer}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsInt extends TypeSafeDiagnosingMatcher<Int> {
  final Matcher<Int> matcher;

  IsInt(Matcher<Int> matcher) {
    this.matcher = matcher;
  }

  @Override
  public void describeTo(Description description) {
    matcher.describeTo(description);
  }

  @Override
  protected boolean matchesSafely(Int value, Description description) {
    return matcher.matches(value);
  }

  public static IsInt isInt(int value) {
    return new IsInt(Is.is(Int.valueOf(value)));
  }
}
