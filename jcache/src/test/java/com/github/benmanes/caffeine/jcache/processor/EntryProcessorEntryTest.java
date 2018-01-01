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
package com.github.benmanes.caffeine.jcache.processor;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

import java.util.Map;
import java.util.Optional;

import javax.cache.Cache;

import org.testng.annotations.Test;

import com.google.common.collect.Maps;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class EntryProcessorEntryTest {
  EntryProcessorEntry<Integer, Integer> entry = new EntryProcessorEntry<>(1, 2, Optional.empty());

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void unwrap_fail() {
    entry.unwrap(Map.Entry.class);
  }

  @Test
  public void unwrap() {
    assertThat(entry.unwrap(Cache.Entry.class), sameInstance(entry));
  }

  @Test
  public void equals() {
    assertThat(entry, is(equalTo(entry)));
  }

  @Test
  public void hash() {
    assertThat(entry.hashCode(), is(entry.hashCode()));
  }

  @Test
  public void string() {
    assertThat(entry, hasToString(Maps.immutableEntry(1, 2).toString()));
  }
}
