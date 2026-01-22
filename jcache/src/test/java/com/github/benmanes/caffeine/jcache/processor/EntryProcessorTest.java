/*
 * Copyright 2017 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.jcache.JCacheFixture.KEY_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.nullRef;
import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;
import static javax.cache.expiry.Duration.FIVE_MINUTES;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;

import javax.cache.Cache;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheWriter;
import javax.cache.processor.MutableEntry;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.jcache.JCacheFixture;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author chrisstockton (Chris Stockton)
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class EntryProcessorTest {

  private static JCacheFixture jcacheFixture(MapLoader loader, MapWriter writer) {
    return JCacheFixture.builder()
        .loading(config -> {
          config.setExpiryPolicyFactory(() -> new CreatedExpiryPolicy(FIVE_MINUTES));
          config.setCacheLoaderFactory(() -> loader);
          config.setCacheWriterFactory(() -> writer);
          config.setMaximumSize(OptionalLong.of(200));
          config.setWriteThrough(true);
          config.setReadThrough(true);
        }).build();
  }

  @Test
  void reload() {
    var map = new HashMap<Integer, Integer>();
    var loader = new MapLoader(map);
    var writer = new MapWriter(map);
    try (var fixture = jcacheFixture(loader, writer)) {
      var value1 = fixture.jcacheLoading().invoke(KEY_1, (entry, arguments) -> process(entry));
      assertThat(loader.loads).isEqualTo(1);
      assertThat(value1).isNull();

      fixture.ticker().advance(Duration.ofMinutes(1));
      var value2 = fixture.jcacheLoading().invoke(KEY_1, (entry, arguments) -> process(entry));
      assertThat(loader.loads).isEqualTo(1);
      assertThat(value2).isNull();

      // Expire the entry
      fixture.ticker().advance(Duration.ofMinutes(5));

      var value3 = fixture.jcacheLoading().invoke(KEY_1, (entry, arguments) -> process(entry));
      assertThat(loader.loads).isEqualTo(2);
      assertThat(value3).isNull();

      fixture.ticker().advance(Duration.ofMinutes(1));
      var value4 = fixture.jcacheLoading().invoke(KEY_1, (entry, arguments) -> process(entry));
      assertThat(loader.loads).isEqualTo(2);
      assertThat(value4).isNull();
    }
  }

  @Test
  void writeOccursForInitialLoadOfEntry() {
    var map = new HashMap<Integer, Integer>();
    var loader = new MapLoader(map);
    var writer = new MapWriter(map);
    try (var fixture = jcacheFixture(loader, writer)) {
      map.put(KEY_1, 100);
      var value = fixture.jcacheLoading().invoke(KEY_1, (entry, arguments) -> process(entry));
      assertThat(writer.writes).isEqualTo(1);
      assertThat(loader.loads).isEqualTo(1);
      assertThat(value).isNull();
    }
  }

  @SuppressFBWarnings("AI_ANNOTATION_ISSUES_NEEDS_NULLABLE")
  private static Object process(MutableEntry<Integer, Integer> entry) {
    var value = 1 + firstNonNull(entry.getValue(), 0);
    entry.setValue(value);
    return nullRef();
  }

  @SuppressWarnings("ImmutableMemberCollection")
  private static final class MapLoader implements CacheLoader<Integer, Integer> {
    private final Map<Integer, Integer> map;

    private int loads;

    MapLoader(Map<Integer, Integer> map) {
      this.map = requireNonNull(map);
    }
    @Override public @Nullable Integer load(Integer key) {
      loads++;
      return map.get(key);
    }
    @Override public ImmutableMap<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
      return Streams.stream(keys).collect(
          toImmutableMap(identity(), key -> requireNonNull(load(key))));
    }
  }

  private static final class MapWriter implements CacheWriter<Integer, Integer> {
    private final Map<Integer, Integer> map;

    private int writes;

    MapWriter(Map<Integer, Integer> map) {
      this.map = requireNonNull(map);
    }
    @Override public void write(Cache.Entry<? extends Integer, ? extends Integer> entry) {
      writes++;
      map.put(entry.getKey(), entry.getValue());
    }
    @Override public void writeAll(
        Collection<Cache.Entry<? extends Integer, ? extends Integer>> entries) {
      entries.forEach(this::write);
    }
    @SuppressWarnings("SuspiciousMethodCalls")
    @Override public void delete(Object key) {
      map.remove(key);
    }
    @Override public void deleteAll(Collection<?> keys) {
      keys.forEach(this::delete);
    }
  }
}
