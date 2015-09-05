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
package com.github.benmanes.caffeine.jcache.expiry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;

import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JCacheExpiryPolicyTest {
  final ExpiryPolicy eternal = new JCacheExpiryPolicy(
      Duration.ETERNAL, Duration.ETERNAL, Duration.ETERNAL);
  final ExpiryPolicy temporal = new JCacheExpiryPolicy(
      Duration.ONE_DAY, Duration.ONE_HOUR, Duration.ONE_MINUTE);

  @Test
  public void creation() {
    assertThat(eternal.getExpiryForCreation(), is(Duration.ETERNAL));
    assertThat(temporal.getExpiryForCreation(), is(Duration.ONE_DAY));
  }

  @Test
  public void update() {
    assertThat(eternal.getExpiryForUpdate(), is(Duration.ETERNAL));
    assertThat(temporal.getExpiryForUpdate(), is(Duration.ONE_HOUR));
  }

  @Test
  public void access() {
    assertThat(eternal.getExpiryForAccess(), is(Duration.ETERNAL));
    assertThat(temporal.getExpiryForAccess(), is(Duration.ONE_MINUTE));
  }

  @Test
  public void equals_wrongType() {
    assertThat(eternal, is(not(1)));
  }

  @Test
  public void equals_wrongValue() {
    assertThat(eternal, is(not(temporal)));
  }

  @Test
  public void equals() {
    assertThat(eternal, is(eternal));
  }

  @Test
  public void hash() {
    assertThat(eternal.hashCode(), is(not(temporal.hashCode())));
  }
}
