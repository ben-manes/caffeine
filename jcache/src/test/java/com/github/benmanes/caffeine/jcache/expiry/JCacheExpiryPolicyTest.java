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

import static com.google.common.truth.Truth.assertThat;

import java.util.HashSet;
import java.util.List;

import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;

import org.testng.annotations.Test;

import com.google.common.testing.EqualsTester;

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
    assertThat(eternal.getExpiryForCreation()).isEqualTo(Duration.ETERNAL);
    assertThat(temporal.getExpiryForCreation()).isEqualTo(Duration.ONE_DAY);
  }

  @Test
  public void update() {
    assertThat(eternal.getExpiryForUpdate()).isEqualTo(Duration.ETERNAL);
    assertThat(temporal.getExpiryForUpdate()).isEqualTo(Duration.ONE_HOUR);
  }

  @Test
  public void access() {
    assertThat(eternal.getExpiryForAccess()).isEqualTo(Duration.ETERNAL);
    assertThat(temporal.getExpiryForAccess()).isEqualTo(Duration.ONE_MINUTE);
  }

  @Test
  public void equals_wrongType() {
    assertThat(eternal.equals(new Object())).isFalse();
  }

  @Test
  public void equals() {
    var tester = new EqualsTester();
    var durations = List.of(Duration.ETERNAL, Duration.ONE_DAY, Duration.ONE_HOUR);
    for (var creation : durations) {
      for (var update : durations) {
        for (var read : durations) {
          tester.addEqualityGroup(
              new JCacheExpiryPolicy(creation, update, read),
              new JCacheExpiryPolicy(creation, update, read));
        }
      }
    }
    tester.testEquals();
  }

  @Test
  public void hash() {
    var hashes = new HashSet<Integer>();
    var durations = List.of(Duration.ETERNAL, Duration.ONE_DAY, Duration.ONE_HOUR);
    for (var creation : durations) {
      for (var update : durations) {
        for (var read : durations) {
          var policy = new JCacheExpiryPolicy(creation, update, read);
          assertThat(hashes).doesNotContain(policy.hashCode());
          hashes.add(policy.hashCode());
        }
      }
    }
  }
}
