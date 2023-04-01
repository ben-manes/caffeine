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
package com.github.benmanes.caffeine.jcache.expiry;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryRemovedListener;

import org.mockito.Mockito;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.jcache.AbstractJCacheTest;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * The test cases that ensures the <tt>variable expiry</tt> policy and <tt>maximum size</tt>
 * settings are set simultaneously.
 *
 * @author github.com/kdombeck (Ken Dombeck)
 */
@Test(singleThreaded = true)
@SuppressWarnings("unchecked")
public class JCacheExpiryAndMaximumSizeTest extends AbstractJCacheTest {
  private static final long ONE_MINUTE = TimeUnit.MINUTES.toNanos(1);
  private static final int MAXIMUM = 10;

  private final Expiry<Integer, Integer> expiry = Mockito.mock();
  private final AtomicInteger removed = new AtomicInteger();

  @BeforeMethod
  public void setup() {
    Mockito.reset(expiry);
    when(expiry.expireAfterCreate(anyInt(), anyInt(), anyLong())).thenReturn(ONE_MINUTE);
    when(expiry.expireAfterUpdate(anyInt(), anyInt(), anyLong(), anyLong())).thenReturn(ONE_MINUTE);
    when(expiry.expireAfterRead(anyInt(), anyInt(), anyLong(), anyLong())).thenReturn(ONE_MINUTE);
  }

  @Override
  protected CaffeineConfiguration<Integer, Integer> getConfiguration() {
    CacheEntryRemovedListener<Integer, Integer> listener = events -> removed.incrementAndGet();

    var configuration = new CaffeineConfiguration<Integer, Integer>();

    configuration.setMaximumSize(OptionalLong.of(MAXIMUM));
    var listenerConfiguration = new MutableCacheEntryListenerConfiguration<>(() -> listener,
        /* filterFactory */ null, /* isOldValueRequired */ false, /* isSynchronous */ true);
    configuration.addCacheEntryListenerConfiguration(listenerConfiguration);
    configuration.setExecutorFactory(MoreExecutors::directExecutor);

    configuration.setExpiryFactory(Optional.of(() -> expiry));
    configuration.setTickerFactory(() -> ticker::read);

    return configuration;
  }

  @Test
  public void expiry() {
    jcache.put(KEY_1, VALUE_1);
    verify(expiry).expireAfterCreate(anyInt(), anyInt(), anyLong());

    jcache.put(KEY_1, VALUE_2);
    verify(expiry).expireAfterUpdate(anyInt(), anyInt(), anyLong(), anyLong());

    var value = jcache.get(KEY_1);
    assertThat(value).isEqualTo(VALUE_2);
    verify(expiry).expireAfterRead(anyInt(), anyInt(), anyLong(), anyLong());
  }

  @Test
  public void size() {
    for (int i = 0; i < 2 * MAXIMUM; i++) {
      jcache.put(i, i);
    }
    assertThat(removed.get()).isEqualTo(MAXIMUM);
  }
}
