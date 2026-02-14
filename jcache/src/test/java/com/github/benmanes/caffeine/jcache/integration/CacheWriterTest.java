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
package com.github.benmanes.caffeine.jcache.integration;

import static com.github.benmanes.caffeine.jcache.JCacheFixture.ENTRIES;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.KEY_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_2;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.cache.Cache;
import javax.cache.integration.CacheWriter;
import javax.cache.integration.CacheWriterException;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.github.benmanes.caffeine.jcache.EntryProxy;
import com.github.benmanes.caffeine.jcache.JCacheFixture;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.google.common.collect.ImmutableList;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class CacheWriterTest {

  private static JCacheFixture jcacheFixture(CloseableCacheWriter writer) {
    Mockito.reset(writer);
    return JCacheFixture.builder()
        .configure(config -> {
          config.setCacheWriterFactory(() -> writer);
          config.setWriteThrough(true);
        }).build();
  }

  @Test
  void put() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().put(KEY_1, VALUE_1);
      verify(writer).write(new EntryProxy<>(KEY_1, VALUE_1));
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void put_fails() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      doThrow(CacheWriterException.class).when(writer).write(any());
      assertThrows(CacheWriterException.class, () -> fixture.jcache().put(KEY_1, VALUE_1));
    }
  }

  @Test
  void putAll() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().putAll(ENTRIES);

      var entries = toCacheEntries(ENTRIES);
      verify(writer).writeAll(entries);
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void putAll_empty() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().putAll(Map.of());
      verifyNoInteractions(writer);
    }
  }

  @Test
  void putAll_fails() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      doThrow(CacheWriterException.class).when(writer).writeAll(any());
      var map = new HashMap<Integer, Integer>();
      map.put(KEY_1, VALUE_1);

      assertThrows(CacheWriterException.class, () -> fixture.jcache().putAll(map));
      assertThat(map).containsExactly(KEY_1, VALUE_1);
    }
  }

  @Test
  void putAll_fails_immutable() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      doThrow(CacheWriterException.class).when(writer).writeAll(any());
      var map = Map.of(KEY_1, VALUE_1);

      assertThrows(CacheWriterException.class, () -> fixture.jcache().putAll(map));
      assertThat(map).containsExactly(KEY_1, VALUE_1);
    }
  }

  @Test
  void replace() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().put(KEY_1, VALUE_1);
      assertThat(fixture.jcache().replace(KEY_1, VALUE_2)).isTrue();

      verify(writer).write(new EntryProxy<>(KEY_1, VALUE_1));
      verify(writer).write(new EntryProxy<>(KEY_1, VALUE_2));
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void replace_fails() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().put(KEY_1, VALUE_1);

      doThrow(CacheWriterException.class).when(writer).write(any());
      assertThrows(CacheWriterException.class, () ->
          fixture.jcache().replace(KEY_1, VALUE_2));
    }
  }

  @Test
  void replaceConditionally() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().put(KEY_1, VALUE_1);
      assertThat(fixture.jcache().replace(KEY_1, VALUE_1, VALUE_2)).isTrue();

      verify(writer).write(new EntryProxy<>(KEY_1, VALUE_1));
      verify(writer).write(new EntryProxy<>(KEY_1, VALUE_2));
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void replaceConditionally_fails() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().put(KEY_1, VALUE_1);

      doThrow(CacheWriterException.class).when(writer).write(any());
      assertThrows(CacheWriterException.class, () ->
          fixture.jcache().replace(KEY_1, VALUE_1, VALUE_2));
    }
  }

  @Test
  void remove() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().put(KEY_1, VALUE_1);
      assertThat(fixture.jcache().remove(KEY_1)).isTrue();

      verify(writer).write(new EntryProxy<>(KEY_1, VALUE_1));
      verify(writer).delete(KEY_1);
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void remove_fails() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().put(KEY_1, VALUE_1);

      doThrow(CacheWriterException.class).when(writer).delete(any());
      assertThrows(CacheWriterException.class, () -> fixture.jcache().remove(KEY_1));
    }
  }

  @Test
  void removeConditionally() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.jcache().remove(KEY_1, VALUE_1);

      verify(writer).write(new EntryProxy<>(KEY_1, VALUE_1));
      verify(writer).delete(KEY_1);
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void removeConditionally_fails() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().put(KEY_1, VALUE_1);

      doThrow(CacheWriterException.class).when(writer).delete(any());
      assertThrows(CacheWriterException.class, () -> fixture.jcache().remove(KEY_1, VALUE_1));
    }
  }

  @Test
  void removeAll() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().putAll(ENTRIES);

      var entries = toCacheEntries(ENTRIES);
      verify(writer).writeAll(entries);

      fixture.jcache().removeAll(ENTRIES.keySet());
      verify(writer).deleteAll(ENTRIES.keySet());
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void removeAll_empty() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().putAll(ENTRIES);

      var entries = toCacheEntries(ENTRIES);
      verify(writer).writeAll(entries);

      fixture.jcache().removeAll(Set.of());
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void removeAll_fails() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      doThrow(CacheWriterException.class).when(writer).deleteAll(any());
      var keys = new HashSet<Integer>();
      keys.add(KEY_1);

      assertThrows(CacheWriterException.class, () -> fixture.jcache().removeAll(keys));
    }
  }

  @Test
  void clear() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      fixture.jcache().putAll(ENTRIES);

      var entries = toCacheEntries(ENTRIES);
      verify(writer).writeAll(entries);

      fixture.jcache().clear();
      verifyNoMoreInteractions(writer);
    }
  }

  @Test
  void close_fails() throws IOException {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      doThrow(IOException.class).when(writer).close();
      fixture.jcache().close();

      verify(writer, atLeastOnce()).close();
    }
  }

  @Test
  void hasCacheWriter() {
    CloseableCacheWriter writer = Mockito.mock();
    try (var fixture = jcacheFixture(writer)) {
      var noWriter = new CaffeineConfiguration<>(fixture.jcacheConfiguration())
          .setCacheWriterFactory(null);
      assertThat(noWriter.hasCacheWriter()).isFalse();
      assertThat(fixture.jcacheConfiguration().hasCacheWriter()).isTrue();
    }
  }

  @Test
  void disabled() {
    DisabledCacheWriter.get().write(null);
    DisabledCacheWriter.get().writeAll(null);
    DisabledCacheWriter.get().delete(null);
    DisabledCacheWriter.get().deleteAll(null);
  }

  private static ImmutableList<Cache.Entry<? extends Integer, ? extends Integer>> toCacheEntries(
      Map<Integer, Integer> map) {
    return map.entrySet().stream()
        .map(entry -> new EntryProxy<>(entry.getKey(), entry.getValue()))
        .collect(toImmutableList());
  }

  private interface CloseableCacheWriter extends CacheWriter<Integer, Integer>, Closeable {}
}
