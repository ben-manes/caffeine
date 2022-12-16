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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import javax.cache.integration.CacheWriter;
import javax.cache.integration.CacheWriterException;

import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.AbstractJCacheTest;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Test(singleThreaded = true)
public final class CacheWriterTest extends AbstractJCacheTest {
  private CloseableCacheWriter writer = Mockito.mock(CloseableCacheWriter.class);

  @Override
  protected CaffeineConfiguration<Integer, Integer> getConfiguration() {
    Mockito.reset(writer);
    var configuration = new CaffeineConfiguration<Integer, Integer>();
    configuration.setCacheWriterFactory(() -> writer);
    configuration.setWriteThrough(true);
    return configuration;
  }

  @Test(expectedExceptions = CacheWriterException.class)
  public void put_fails() {
    doThrow(CacheWriterException.class).when(writer).write(any());
    jcache.put(KEY_1, VALUE_1);
  }

  @Test
  public void putAll_fails() {
    doThrow(CacheWriterException.class).when(writer).writeAll(any());
    var map = new HashMap<Integer, Integer>();
    map.put(KEY_1, VALUE_1);

    try {
      jcache.putAll(map);
      Assert.fail();
    } catch (CacheWriterException e) {
      assertThat(map).isEmpty();
    }
  }

  @Test
  public void putAllImmutable_fails() {
    doThrow(CacheWriterException.class).when(writer).writeAll(any());
    var immutableMap = Map.of(KEY_1, VALUE_1);

    try {
      jcache.putAll(immutableMap);
      Assert.fail();
    } catch (CacheWriterException e) {
      assertThat(immutableMap).isEmpty();
    }
  }

  @Test(expectedExceptions = CacheWriterException.class)
  public void remove_fails() {
    jcache.put(KEY_1, VALUE_1);

    doThrow(CacheWriterException.class).when(writer).delete(any());
    jcache.remove(KEY_1);
  }

  @Test(expectedExceptions = CacheWriterException.class)
  public void removeAll_fails() {
    doThrow(CacheWriterException.class).when(writer).deleteAll(any());
    var keys = new HashSet<Integer>();
    keys.add(KEY_1);

    jcache.removeAll(keys);
  }

  @Test
  public void close_fails() throws IOException {
    doThrow(IOException.class).when(writer).close();
    jcache.close();

    verify(writer, atLeastOnce()).close();
  }

  @Test
  public void disabled() {
    DisabledCacheWriter.get().write(null);
    DisabledCacheWriter.get().writeAll(null);
    DisabledCacheWriter.get().delete(null);
    DisabledCacheWriter.get().deleteAll(null);
  }

  interface CloseableCacheWriter extends CacheWriter<Integer, Integer>, Closeable {}
}
