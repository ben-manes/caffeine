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

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

import javax.cache.Cache.Entry;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheLoaderException;
import javax.cache.integration.CacheWriter;
import javax.cache.processor.MutableEntry;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.AbstractJCacheTest;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Streams;

/**
 * @author chrisstockton (Chris Stockton)
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class EntryProcessorTest extends AbstractJCacheTest {
  private final Map<Integer, Integer> map = new HashMap<>();

  private int loads;
  private int writes;

  @BeforeMethod
  public void beforeMethod() {
    map.clear();
    loads = 0;
    writes = 0;
  }

  @Override
  protected CaffeineConfiguration<Integer, Integer> getConfiguration() {
    CaffeineConfiguration<Integer, Integer> config = new CaffeineConfiguration<>();
    config.setExpiryPolicyFactory(() -> new CreatedExpiryPolicy(Duration.FIVE_MINUTES));
    config.setCacheLoaderFactory(MapLoader::new);
    config.setCacheWriterFactory(MapWriter::new);
    config.setTickerFactory(() -> ticker::read);
    config.setMaximumSize(OptionalLong.of(200));
    config.setWriteThrough(true);
    config.setReadThrough(true);
    return config;
  }

  @Test
  public void reload() {
    jcache.invoke(KEY_1, this::process);
    assertThat(loads, is(1));

    ticker.advance(1, TimeUnit.MINUTES);
    jcache.invoke(KEY_1, this::process);
    assertThat(loads, is(1));

    // Expire the entry
    ticker.advance(5, TimeUnit.MINUTES);

    jcache.invoke(KEY_1, this::process);
    assertThat(loads, is(2));

    ticker.advance(1, TimeUnit.MINUTES);
    jcache.invoke(KEY_1, this::process);
    assertThat(loads, is(2));
  }

  @Test
  public void writeOccursForInitialLoadOfEntry() {
    map.put(KEY_1, 100);
    jcache.invoke(KEY_1, this::process);
    assertThat(loads, is(1));
    assertThat(writes, is(1));
  }

  private Object process(MutableEntry<Integer, Integer> entry, Object... arguments) {
    Integer value = MoreObjects.firstNonNull(entry.getValue(), 0);
    entry.setValue(++value);
    return null;
  }

  final class MapWriter implements CacheWriter<Integer, Integer> {

    @Override
    public void write(Entry<? extends Integer, ? extends Integer> entry) {
      writes++;
      map.put(entry.getKey(), entry.getValue());
    }

    @Override
    public void writeAll(Collection<Entry<? extends Integer, ? extends Integer>> entries) {
      entries.forEach(this::write);
    }

    @Override
    public void delete(Object key) {
      map.remove(key);
    }

    @Override
    public void deleteAll(Collection<?> keys) {
      keys.forEach(this::delete);
    }
  }

  final class MapLoader implements CacheLoader<Integer, Integer> {

    @Override
    public Integer load(Integer key) throws CacheLoaderException {
      loads++;
      return map.get(key);
    }

    @Override
    public Map<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
      return Streams.stream(keys).collect(toMap(identity(), this::load));
    }
  }
}
