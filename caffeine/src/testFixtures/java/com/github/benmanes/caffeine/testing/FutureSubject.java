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
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.jspecify.annotations.Nullable;

import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Subject;
import com.google.common.truth.ThrowableSubject;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * Propositions for {@link CompletableFuture} subjects.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class FutureSubject extends Subject {
  private final @Nullable CompletableFuture<?> actual;

  private FutureSubject(FailureMetadata metadata, @Nullable CompletableFuture<?> subject) {
    super(metadata, subject);
    this.actual = subject;
  }

  public static Factory<FutureSubject, CompletableFuture<?>> future() {
    return FutureSubject::new;
  }

  public static FutureSubject assertThat(@Nullable CompletableFuture<?> actual) {
    return assertAbout(future()).that(actual);
  }

  /** Fails if the future is not done. */
  public void isDone() {
    requireNonNull(actual);
    if (!actual.isDone()) {
      failWithActual("expected to be done", actual);
    }
  }

  /** Fails if the future is done. */
  public void isNotDone() {
    requireNonNull(actual);
    if (actual.isDone()) {
      failWithActual("expected to not be done", actual);
    }
  }

  /** Fails if the future is has not completed exceptionally. */
  public void hasCompletedExceptionally() {
    requireNonNull(actual);
    if (!actual.isCompletedExceptionally()) {
      failWithActual("expected to be completed exceptionally", actual.join());
    }
  }

  /** Fails if the future is not successful with the given value. */
  public void succeedsWith(int value) {
    requireNonNull(actual);
    var result = actual.join();
    if (result instanceof Int) {
      check("future").that(result).isEqualTo(Int.valueOf(value));
    } else {
      check("future").that(result).isEqualTo(value);
    }
  }

  /** Fails if the future is not successful with the given value. */
  public void succeedsWith(@Nullable Object value) {
    requireNonNull(actual);
    check("future").that(actual.join()).isEqualTo(value);
  }

  /** Fails if the future is not successful with a null value. */
  public void succeedsWithNull() {
    requireNonNull(actual);
    check("future").that(actual.join()).isNull();
  }

  /** Fails if the future is did not fail with the given join() exception. */
  @CanIgnoreReturnValue
  public ThrowableSubject failsWith(Class<? extends RuntimeException> clazz) {
    requireNonNull(actual);
    try {
      failWithActual("join", actual.join());
      throw new AssertionError();
    } catch (CompletionException | CancellationException e) {
      check("future").that(e).isInstanceOf(clazz);
      return check("future").that(e);
    }
  }
}
