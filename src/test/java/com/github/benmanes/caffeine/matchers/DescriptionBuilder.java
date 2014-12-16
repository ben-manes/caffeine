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
package com.github.benmanes.caffeine.matchers;

import org.hamcrest.Description;
import org.hamcrest.Matcher;

import com.google.common.base.Throwables;

/**
 * Assists in implementing {@link org.hamcrest.Matcher}s.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class DescriptionBuilder {
  private final Description description;
  private boolean matches;

  public DescriptionBuilder(Description description) {
    this.description = description;
    this.matches = true;
  }

  public <T> void expectThat(T actual, Matcher<? super T> matcher) {
    expectThat("", actual, matcher);
  }

  public <T> void expectThat(String reason, T actual, Matcher<? super T> matcher) {
    if (!matcher.matches(actual)) {
      description.appendText(reason)
        .appendText("\nExpected: ")
        .appendDescriptionOf(matcher)
        .appendText("\n     but: ");
      matcher.describeMismatch(actual, description);
      description.appendText("\nLocation: ")
        .appendText(Throwables.getStackTraceAsString(new Exception()));

      matches = false;
    }
  }

  public <T> void expected(String reason) {
    description.appendText(reason).appendText("\nExpected to not be reachable");
    description.appendText("\nLocation: ").appendText(
        Throwables.getStackTraceAsString(new Exception()));
    matches = false;
  }

  public Description getDescription() {
    return description;
  }

  public boolean matches() {
    return matches;
  }
}
