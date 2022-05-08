/*
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
package com.github.benmanes.caffeine.jcache;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.io.Closeable;
import java.io.IOException;

import javax.cache.Cache;
import javax.cache.configuration.Configuration;
import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryListener;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheWriter;

import org.mockito.Mockito;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;

/**
 * @author github.com/kdombeck (Ken Dombeck)
 */
public final class CacheProxyTest extends AbstractJCacheTest {
  private CloseableCacheEntryListener listener = Mockito.mock(CloseableCacheEntryListener.class);
  private CloseableExpiryPolicy expiry = Mockito.mock(CloseableExpiryPolicy.class);
  private CloseableCacheLoader loader = Mockito.mock(CloseableCacheLoader.class);
  private CloseableCacheWriter writer = Mockito.mock(CloseableCacheWriter.class);

  @Override
  protected CaffeineConfiguration<Integer, Integer> getConfiguration() {
    Mockito.reset(listener, expiry, loader, writer);

    var configuration = new CaffeineConfiguration<Integer, Integer>();
    configuration.setExpiryPolicyFactory(() -> expiry);
    configuration.setCacheLoaderFactory(() -> loader);
    configuration.setCacheWriterFactory(() -> writer);
    configuration.setWriteThrough(true);
    configuration.setReadThrough(true);
    return configuration;
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void unwrap_fail() {
    jcache.unwrap(CaffeineConfiguration.class);
  }

  @Test
  public void unwrap() {
    assertThat(jcache.unwrap(Cache.class)).isSameInstanceAs(jcache);
    assertThat(jcache.unwrap(CacheProxy.class)).isSameInstanceAs(jcache);
    assertThat(jcache.unwrap(com.github.benmanes.caffeine.cache.Cache.class))
        .isSameInstanceAs(jcache.cache);
  }

  @SuppressWarnings("serial")
  @Test(expectedExceptions = IllegalArgumentException.class)
  public void unwrap_configuration() {
    abstract class Dummy implements Configuration<Integer, Integer> {};
    jcache.getConfiguration(Dummy.class);
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void unwrap_entry() {
    jcache.put(KEY_1, VALUE_1);
    jcache.iterator().next().unwrap(String.class);
  }

  @Test
  public void close_fails() throws IOException {
    doThrow(IOException.class).when(expiry).close();
    doThrow(IOException.class).when(loader).close();
    doThrow(IOException.class).when(writer).close();
    doThrow(IOException.class).when(listener).close();

    var configuration = new MutableCacheEntryListenerConfiguration<Integer, Integer>(
        /* listener */ () -> listener, /* filter */ () -> event -> true,
        /* isOldValueRequired */ false, /* isSynchronous */ false);
    jcache.registerCacheEntryListener(configuration);

    jcache.close();
    verify(expiry, atLeastOnce()).close();
    verify(loader, atLeastOnce()).close();
    verify(writer, atLeastOnce()).close();
    verify(listener, atLeastOnce()).close();
  }

  interface CloseableExpiryPolicy extends ExpiryPolicy, Closeable {}
  interface CloseableCacheLoader extends CacheLoader<Integer, Integer>, Closeable {}
  interface CloseableCacheWriter extends CacheWriter<Integer, Integer>, Closeable {}
  interface CloseableCacheEntryListener extends CacheEntryListener<Integer, Integer>, Closeable {}
}
