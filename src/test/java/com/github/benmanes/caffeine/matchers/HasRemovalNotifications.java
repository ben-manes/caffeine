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

import static com.google.common.base.Preconditions.checkNotNull;
import static org.hamcrest.Matchers.hasSize;

import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalNotification;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.RemovalListeners.ConsumingRemovalListener;

/**
 * A matcher that evaluates the {@link ConsumingRemovalListener} recorded all of the removal
 * notifications.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class HasRemovalNotifications<K, V> extends TypeSafeDiagnosingMatcher<Cache<K, V>> {
  private final RemovalCause cause;
  private final CacheContext context;

  public HasRemovalNotifications(CacheContext context, @Nullable RemovalCause cause) {
    this.context = checkNotNull(context);
    this.cause = cause;
  }

  @Override
  public void describeTo(Description description) {
    description.appendText("consumed");
  }

  @Override
  protected boolean matchesSafely(Cache<K, V> cache, Description description) {
    DescriptionBuilder builder = new DescriptionBuilder(description);

    if (context.removalListenerType() == Listener.CONSUMING) {
      ForkJoinPool.commonPool().awaitQuiescence(10, TimeUnit.SECONDS);
      int removed = (int) (context.initialSize() - cache.size());
      ConsumingRemovalListener<Integer, Integer> removalListener = context.removalListener();
      builder.expectThat(removalListener.evicted(), hasSize(removed));

      // TODO(ben): Inspect the notifications to assert removals
      checkRemovalCause(removalListener.evicted(), builder);
    }

    return builder.matches();
  }

  private void checkRemovalCause(List<RemovalNotification<Integer, Integer>> list,
      DescriptionBuilder builder) {
    if (cause == null) {
      return;
    }
    // TODO(ben): Inspect notifications to assert the cause
  }

  @Factory
  public static <K, V> HasRemovalNotifications<K, V> hasRemovalNotifications(CacheContext context) {
    return new HasRemovalNotifications<K, V>(context, null);
  }

  @Factory
  public static <K, V> HasRemovalNotifications<K, V> hasRemovalNotifications(
      CacheContext context, RemovalCause cause) {
    return new HasRemovalNotifications<K, V>(context, cause);
  }
}
