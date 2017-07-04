/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.mockito.Mockito;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.jcache.AbstractJCacheTest;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;

/**
 * The test cases that ensure the <tt>variable expiry</tt> policy is configured.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Test(singleThreaded = true)
@SuppressWarnings("unchecked")
public final class JCacheExpiryTest extends AbstractJCacheTest {
  private static final long ONE_MINUTE = TimeUnit.MINUTES.toNanos(1);

  private Expiry<Integer, Integer> expiry = Mockito.mock(Expiry.class);

  @BeforeMethod
  public void setup() {
    Mockito.reset(expiry);
    when(expiry.expireAfterCreate(anyInt(), anyInt(), anyLong())).thenReturn(ONE_MINUTE);
    when(expiry.expireAfterUpdate(anyInt(), anyInt(), anyLong(), anyLong())).thenReturn(ONE_MINUTE);
    when(expiry.expireAfterRead(anyInt(), anyInt(), anyLong(), anyLong())).thenReturn(ONE_MINUTE);
  }

  @Override
  protected CaffeineConfiguration<Integer, Integer> getConfiguration() {
    CaffeineConfiguration<Integer, Integer> configuration = new CaffeineConfiguration<>();
    configuration.setExpiryFactory(Optional.of(() -> expiry));
    configuration.setTickerFactory(() -> ticker::read);
    return configuration;
  }

  @Test
  public void configured() {
    jcache.put(KEY_1, VALUE_1);
    verify(expiry, times(1)).expireAfterCreate(anyInt(), anyInt(), anyLong());

    jcache.put(KEY_1, VALUE_2);
    verify(expiry).expireAfterUpdate(anyInt(), anyInt(), anyLong(), anyLong());

    jcache.get(KEY_1);
    verify(expiry).expireAfterRead(anyInt(), anyInt(), anyLong(), anyLong());
  }
}
