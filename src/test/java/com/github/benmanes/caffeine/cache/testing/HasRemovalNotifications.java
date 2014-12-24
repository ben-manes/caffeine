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
package com.github.benmanes.caffeine.cache.testing;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalNotification;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.RemovalListeners.ConsumingRemovalListener;
import com.github.benmanes.caffeine.matchers.DescriptionBuilder;

/**
 * A matcher that evaluates the {@link ConsumingRemovalListener} recorded all of the removal
 * notifications.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class HasRemovalNotifications<K, V> extends TypeSafeDiagnosingMatcher<Cache<K, V>> {
  private final int count;
  private final RemovalCause cause;
  private final CacheContext context;

  public HasRemovalNotifications(CacheContext context, int count, @Nullable RemovalCause cause) {
    this.context = checkNotNull(context);
    this.cause = cause;
    this.count = count;
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
      ConsumingRemovalListener<Integer, Integer> removalListener = context.removalListener();
      builder.expectThat(removalListener.evicted(), hasSize(count));

      for (RemovalNotification<?, ?> notification : removalListener.evicted()) {
        checkNotification(notification);
      }
    }

    return builder.matches();
  }

  private void checkNotification(RemovalNotification<?, ?> notification) {
    switch (notification.getCause()) {
      case EXPLICIT: case REPLACED:
        assertThat(notification.wasEvicted(), is(false));
        assertThat(notification.getKey(), is(not(nullValue())));
        assertThat(notification.getValue(), is(not(nullValue())));
        break;
      case EXPIRED: case SIZE:
        assertThat(notification.wasEvicted(), is(true));
        assertThat(notification.getKey(), is(not(nullValue())));
        assertThat(notification.getValue(), is(not(nullValue())));
        break;
      case COLLECTED:
        assertThat(notification.wasEvicted(), is(true));
        if (context.keyReferenceType() == ReferenceType.STRONG) {
          assertThat(notification.getKey(), is(not(nullValue())));
        }
        if (context.valueReferenceType() == ReferenceType.STRONG) {
          assertThat(notification.getKey(), is(not(nullValue())));
        }
    }
    if (cause != null) {
      assertThat("Wrong removal cause for " + notification, notification.getCause(), is(cause));
    }
  }

  @Factory
  public static <K, V> HasRemovalNotifications<K, V> hasRemovalNotifications(
      CacheContext context, int count, RemovalCause cause) {
    return new HasRemovalNotifications<K, V>(context, count, cause);
  }
}
