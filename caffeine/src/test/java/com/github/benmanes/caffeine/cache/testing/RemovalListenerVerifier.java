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
package com.github.benmanes.caffeine.cache.testing;

import static com.github.benmanes.caffeine.testing.Awaits.await;
import static java.util.Objects.requireNonNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.iterableWithSize;

import java.util.function.Consumer;
import java.util.function.Supplier;

import org.hamcrest.Matcher;

import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.RemovalListeners.ConsumingRemovalListener;
import com.google.common.primitives.Ints;

/**
 * A utility for verifying that the {@link RemovalListener} was operated on correctly.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class RemovalListenerVerifier {
  private final ConsumingRemovalListener<Integer, Integer> listener;
  private final CacheContext context;

  private RemovalListenerVerifier(CacheContext context,
      ConsumingRemovalListener<Integer, Integer> listener) {
    this.listener = requireNonNull(listener);
    this.context = requireNonNull(context);
  }

  /** Checks that only {@code count} notifications occurred and are of type {@code cause}. */
  public void hasOnly(long count, RemovalCause cause) {
    awaitUntil(listener::removed, iterableWithSize(Ints.checkedCast(count)));
    hasCount(count, cause);
  }

  /** Checks that {@code count} notifications occurred of type {@code cause}. */
  public void hasCount(long count, RemovalCause cause) {
    awaitUntil(() -> {
      return listener.removed().stream()
          .filter(notification -> cause == notification.getCause())
          .count();
    }, is(count));
  }

  /** Checks that no notifications occurred. */
  public void noInteractions() {
    awaitUntil(listener::removed, is(empty()));
  }

  private <T> void awaitUntil(Supplier<T> actual, Matcher<? super T> matcher) {
    if (context.executorType() == CacheExecutor.DIRECT) {
      assertThat(listener.removed().toString(), actual.get(), matcher);
    } else {
      await().until(actual::get, matcher);
    }
  }

  /** Runs the verification block if the consuming listeners are enabled. */
  public static void verifyListeners(CacheContext context,
      Consumer<RemovalListenerVerifier> consumer) {
    verifyRemovalListener(context, consumer);
    verifyEvictionListener(context, consumer);
  }

  /** Runs the verification block if the consuming removal listener is enabled. */
  public static void verifyRemovalListener(CacheContext context,
      Consumer<RemovalListenerVerifier> consumer) {
    if (context.removalListenerType == Listener.CONSUMING) {
      RemovalListenerVerifier verifier = new RemovalListenerVerifier(context,
          (ConsumingRemovalListener<Integer, Integer>) context.removalListener());
      consumer.accept(verifier);
    }
  }

  /** Runs the verification block if the consuming eviction listener is enabled. */
  public static void verifyEvictionListener(CacheContext context,
      Consumer<RemovalListenerVerifier> consumer) {
    if (context.evictionListenerType == Listener.CONSUMING) {
      RemovalListenerVerifier verifier = new RemovalListenerVerifier(context,
          (ConsumingRemovalListener<Integer, Integer>) context.evictionListener());
      consumer.accept(verifier);
    }
  }
}
