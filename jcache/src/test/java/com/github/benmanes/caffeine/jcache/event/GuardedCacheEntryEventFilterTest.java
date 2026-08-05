/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.jcache.event;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import javax.cache.Cache;
import javax.cache.event.CacheEntryEventFilter;
import javax.cache.event.EventType;

import org.apache.commons.lang3.concurrent.UncheckedExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import com.github.valfirst.slf4jtest.TestLoggerFactory;
import com.github.valfirst.slf4jtest.TestLoggerFactoryExtension;
import com.google.common.testing.EqualsTester;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@ExtendWith(TestLoggerFactoryExtension.class)
final class GuardedCacheEntryEventFilterTest {

  @Test
  void evaluate() {
    CacheEntryEventFilter<Integer, Integer> underlying = Mockito.mock();
    var filter = new GuardedCacheEntryEventFilter<>(underlying);
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      when(underlying.evaluate(any()))
          .thenReturn(false)
          .thenReturn(true);
      var event = new JCacheEntryEvent<>(cache, EventType.CREATED,
          /* key= */ 1, /* hasOldValue= */ false, /* oldValue= */ null, /* newValue= */ 2);
      assertThat(filter.evaluate(event)).isFalse();
      assertThat(filter.evaluate(event)).isTrue();
    }
  }

  @Test
  void evaluate_throwsException() {
    CacheEntryEventFilter<Integer, Integer> underlying = Mockito.mock();
    var filter = new GuardedCacheEntryEventFilter<>(underlying);
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      when(underlying.evaluate(any()))
          .thenThrow(AssertionError.class)
          .thenThrow(UncheckedExecutionException.class);
      var event = new JCacheEntryEvent<>(cache, EventType.CREATED,
          /* key= */ 1, /* hasOldValue= */ false, /* oldValue= */ null, /* newValue= */ 2);
      assertThat(filter.evaluate(event)).isFalse();
      assertThat(filter.evaluate(event)).isFalse();
      assertThat(TestLoggerFactory.getLoggingEvents()).hasSize(2);
    }
  }

  @Test
  void equals() {
    CacheEntryEventFilter<Integer, Integer> none = event -> false;
    CacheEntryEventFilter<Integer, Integer> all = event -> true;
    new EqualsTester()
        .addEqualityGroup(new GuardedCacheEntryEventFilter<>(none),
            new GuardedCacheEntryEventFilter<>(none))
        .addEqualityGroup(new GuardedCacheEntryEventFilter<>(all),
            new GuardedCacheEntryEventFilter<>(all))
        .testEquals();
  }
}
