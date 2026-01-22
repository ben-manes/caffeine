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

import static com.github.benmanes.caffeine.jcache.JCacheFixture.KEY_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_2;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.jcache.JCacheFixture;

/**
 * The test cases that ensure the variable expiry policy is configured.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class JCacheExpiryTest {
  private static final long ONE_MINUTE = TimeUnit.MINUTES.toNanos(1);

  private static JCacheFixture jcacheFixture(Expiry<Integer, Integer> expiry) {
    when(expiry.expireAfterCreate(anyInt(), anyInt(), anyLong())).thenReturn(ONE_MINUTE);
    when(expiry.expireAfterUpdate(anyInt(), anyInt(), anyLong(), anyLong())).thenReturn(ONE_MINUTE);
    when(expiry.expireAfterRead(anyInt(), anyInt(), anyLong(), anyLong())).thenReturn(ONE_MINUTE);
    return JCacheFixture.builder()
        .configure(config -> config.setExpiryFactory(Optional.of(() -> expiry)))
        .build();
  }

  @Test
  void configured() {
    Expiry<Integer, Integer> expiry = Mockito.mock();
    try (var fixture = jcacheFixture(expiry)) {
      fixture.jcache().put(KEY_1, VALUE_1);
      verify(expiry).expireAfterCreate(anyInt(), anyInt(), anyLong());

      fixture.jcache().put(KEY_1, VALUE_2);
      verify(expiry).expireAfterUpdate(anyInt(), anyInt(), anyLong(), anyLong());

      var value = fixture.jcache().get(KEY_1);
      assertThat(value).isEqualTo(VALUE_2);
      verify(expiry).expireAfterRead(anyInt(), anyInt(), anyLong(), anyLong());
    }
  }
}
