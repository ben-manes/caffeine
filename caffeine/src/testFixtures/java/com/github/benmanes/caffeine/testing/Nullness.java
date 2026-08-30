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
package com.github.benmanes.caffeine.testing;

import java.lang.ref.ReferenceQueue;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressFBWarnings({"AI_ANNOTATION_ISSUES_NEEDS_NULLABLE", "NP_NONNULL_RETURN_VIOLATION"})
public final class Nullness {

  private Nullness() {}

  public static Int nullKey() {
    return nullRef();
  }

  public static Int nullValue() {
    return nullRef();
  }

  public static Int[] nullArray() {
    return nullRef();
  }

  public static Collection<Int> nullCollection() {
    return nullRef();
  }

  public static Map<Int, Int> nullMap() {
    return nullRef();
  }

  public static String nullString() {
    return nullRef();
  }

  public static Duration nullDuration() {
    return nullRef();
  }

  public static <E extends @Nullable Object> CompletableFuture<E> nullFuture() {
    return nullRef();
  }

  public static <E> ReferenceQueue<E> nullReferenceQueue() {
    return nullRef();
  }

  public static <E extends @Nullable Object> Supplier<E> nullSupplier() {
    return nullRef();
  }

  public static <E extends @Nullable Object> Predicate<E> nullPredicate() {
    return nullRef();
  }

  public static <T extends @Nullable Object, R extends @Nullable Object>
      Function<T, R> nullFunction() {
    return nullRef();
  }

  public static <T extends @Nullable Object> Consumer<T> nullConsumer() {
    return nullRef();
  }

  public static <T extends @Nullable Object, U extends @Nullable Object>
      BiConsumer<T, U> nullBiConsumer() {
    return nullRef();
  }

  public static <T extends @Nullable Object, U extends @Nullable Object,
      R extends @Nullable Object> BiFunction<T, U, R> nullBiFunction() {
    return nullRef();
  }

  /** Returns {@code null} for use when testing null checks while satisfying null analysis tools. */
  @SuppressWarnings({"DataFlowIssue", "NullableProblems",
      "NullAway", "TypeParameterUnusedInFormals"})
  public static <T> @NonNull T nullRef() {
    return null;
  }

  /** Returns {@code null} for use when testing null checks while satisfying null analysis tools. */
  @SuppressWarnings({"DataFlowIssue", "NullableProblems", "NullAway"})
  public static <T> @NonNull T castNonNull(@Nullable T value) {
    return value;
  }
}
