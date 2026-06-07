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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Optional;

import javax.cache.Cache;

import org.junit.jupiter.api.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class EntryProcessorEntryTest {
  private static final EntryProcessorEntry<Integer, Integer> ENTRY =
      new EntryProcessorEntry<>(1, 2, Optional.empty());

  @Test
  void unwrap_fail() {
    assertThrows(IllegalArgumentException.class, () -> ENTRY.unwrap(Map.Entry.class));
  }

  @Test
  void unwrap() {
    assertThat(ENTRY.unwrap(Cache.Entry.class)).isSameInstanceAs(ENTRY);
  }

  @Test
  @SuppressWarnings("EqualsWithItself")
  void equals() {
    assertThat(ENTRY.equals(ENTRY)).isTrue();
  }

  @Test
  void hash() {
    assertThat(ENTRY.hashCode()).isEqualTo(ENTRY.hashCode());
  }

  @Test
  void string() {
    assertThat(ENTRY.toString()).isEqualTo(Map.entry(1, 2).toString());
  }

  @Test
  void remove_onPresentEntry_isDeleted() {
    var entry = new EntryProcessorEntry<>(1, 2, Optional.empty());
    entry.remove();
    assertThat(entry.getAction()).isEqualTo(Action.DELETED);
    assertThat(entry.exists()).isFalse();
    assertThat(entry.getValue()).isNull();
  }

  @Test
  void remove_onMissingEntry_isDeleted() {
    var entry = new EntryProcessorEntry<Integer, Integer>(1, null, Optional.empty());
    entry.remove();
    assertThat(entry.getAction()).isEqualTo(Action.DELETED);
    assertThat(entry.exists()).isFalse();
    assertThat(entry.getValue()).isNull();
  }

  @Test
  void setValue_afterRemove_onPresentEntry_isUpdated() {
    var entry = new EntryProcessorEntry<>(1, 2, Optional.empty());
    entry.remove();
    entry.setValue(3);
    assertThat(entry.getAction()).isEqualTo(Action.UPDATED);
    assertThat(entry.getValue()).isEqualTo(3);
  }

  @Test
  void setValue_afterRemove_onMissingEntry_isCreated() {
    var entry = new EntryProcessorEntry<Integer, Integer>(1, null, Optional.empty());
    entry.setValue(2);
    entry.remove();
    entry.setValue(3);
    assertThat(entry.getAction()).isEqualTo(Action.CREATED);
    assertThat(entry.getValue()).isEqualTo(3);
  }

  @Test
  void setValue_onPresentEntry_isUpdated() {
    var entry = new EntryProcessorEntry<>(1, 2, Optional.empty());
    entry.setValue(3);
    assertThat(entry.getAction()).isEqualTo(Action.UPDATED);
  }

  @Test
  void setValue_onMissingEntry_isCreated() {
    var entry = new EntryProcessorEntry<Integer, Integer>(1, null, Optional.empty());
    entry.setValue(2);
    assertThat(entry.getAction()).isEqualTo(Action.CREATED);
  }
}
