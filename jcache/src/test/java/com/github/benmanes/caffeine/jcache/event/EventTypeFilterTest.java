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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import javax.cache.event.CacheEntryEventFilter;
import javax.cache.event.CacheEntryListener;

import org.mockito.Mockito;
import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("unchecked")
public final class EventTypeFilterTest {
  EventTypeFilter<Integer, Integer> filter = new EventTypeFilter<>(
      Mockito.mock(CacheEntryListener.class), Mockito.mock(CacheEntryEventFilter.class));

  @Test
  public void equals_wrongType() {
    assertThat(filter, is(not(1)));
  }

  @Test
  public void equals_false() {
    EventTypeFilter<Integer, Integer> other = new EventTypeFilter<>(
        Mockito.mock(CacheEntryListener.class), Mockito.mock(CacheEntryEventFilter.class));
    assertThat(filter, is(not(equalTo(other))));
  }

  @Test
  public void equals() {
    assertThat(filter, is(equalTo(filter)));
  }

  @Test
  public void hash() {
    assertThat(filter.hashCode(), is(filter.hashCode()));
  }
}
