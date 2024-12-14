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

import static com.google.common.truth.Truth.assertAbout;

import org.jspecify.annotations.Nullable;

import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Subject;

/**
 * Propositions for {@link Int} subjects.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IntSubject extends Subject {

  private IntSubject(FailureMetadata metadata, @Nullable Int subject) {
    super(metadata, subject);
  }

  public static Factory<IntSubject, Int> integer() {
    return IntSubject::new;
  }

  public static IntSubject assertThat(@Nullable Int actual) {
    return assertAbout(integer()).that(actual);
  }

  public void isEqualTo(int value) {
    isEqualTo(Int.valueOf(value));
  }
}
