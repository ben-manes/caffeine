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

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.RemovalListeners.ConsumingRemovalListener;

/**
 * A matcher that evaluates the
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class HasConsumedEvicted<K, V> extends TypeSafeDiagnosingMatcher<Cache<K, V>> {
  private final CacheContext context;

  public HasConsumedEvicted(CacheContext context) {
    this.context = checkNotNull(context);
  }

  @Override
  public void describeTo(Description description) {
    description.appendText("consumed");
  }

  @Override
  protected boolean matchesSafely(Cache<K, V> cache, Description description) {
    DescriptionBuilder builder = new DescriptionBuilder(description);

    if (context.getRemovalListenerType() == Listener.CONSUMING) {
      ForkJoinPool.commonPool().awaitQuiescence(10, TimeUnit.SECONDS);
      int removed = (int) (context.getInitialSize() - cache.size());
      ConsumingRemovalListener<Integer, Integer> removalListener = context.getRemovalListener();
      builder.expectThat(removalListener.evicted(), hasSize(removed));
    }

    return builder.matches();
  }

  @Factory
  public static <K, V> HasConsumedEvicted<K, V> consumedEvicted(CacheContext context) {
    return new HasConsumedEvicted<K, V>(context);
  }
}
