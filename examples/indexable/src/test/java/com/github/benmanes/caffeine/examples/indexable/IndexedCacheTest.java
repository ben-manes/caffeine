/*
 * Copyright 2024 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.examples.indexable;

import static com.google.common.truth.Truth.assertThat;

import java.time.Duration;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.examples.indexable.IndexedCacheTest.UserKey.UserById;
import com.github.benmanes.caffeine.examples.indexable.IndexedCacheTest.UserKey.UserByLogin;
import com.github.benmanes.caffeine.examples.indexable.IndexedCacheTest.UserKey.UserByPhone;
import com.google.common.testing.FakeTicker;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IndexedCacheTest {
  private final Set<User> users = Set.of(
      new User(1, "john.doe", "+1 (555) 555-5555"),
      new User(2, "jane.doe", "+1 (777) 777-7777"));

  @Test
  public void basicUsage() {
    var ticker = new FakeTicker();
    var cache = new IndexedCache.Builder<UserKey, User>()
        .addSecondaryKey(user -> new UserByLogin(user.login()))
        .addSecondaryKey(user -> new UserByPhone(user.phone()))
        .primaryKey(user -> new UserById(user.id()))
        .expireAfterWrite(Duration.ofMinutes(1))
        .ticker(ticker::read)
        .build(this::findUser);

    var johnById = cache.get(new UserById(1));
    assertThat(johnById).isNotNull();

    var johnByLogin = cache.get(new UserByLogin("john.doe"));
    assertThat(johnByLogin).isSameInstanceAs(johnById);

    var janeByLogin = cache.get(new UserByLogin("jane.doe"));
    assertThat(janeByLogin).isNotSameInstanceAs(johnById);

    assertThat(cache.store.asMap()).hasSize(2);
    assertThat(cache.indexes).hasSize(6);

    cache.invalidate(new UserByPhone("+1 (555) 555-5555"));
    assertThat(cache.getIfPresent(new UserByLogin("john.doe"))).isNull();

    assertThat(cache.store.asMap()).hasSize(1);
    assertThat(cache.indexes).hasSize(3);

    ticker.advance(Duration.ofHours(1));
    cache.store.cleanUp();

    assertThat(cache.store.asMap()).isEmpty();
    assertThat(cache.indexes).isEmpty();
  }

  /** Returns the user found in the system of record. */
  private User findUser(UserKey key) {
    Predicate<User> predicate = switch (key) {
      case UserById(int id) -> user -> user.id() == id;
      case UserByLogin(var login) -> user -> user.login().equals(login);
      case UserByPhone(var phone) -> user -> user.phone().equals(phone);
    };
    return users.stream().filter(predicate).findAny().orElse(null);
  }

  sealed interface UserKey permits UserById, UserByLogin, UserByPhone {
    record UserByLogin(String login) implements UserKey {}
    record UserByPhone(String phone) implements UserKey {}
    record UserById(int id) implements UserKey {}
  }
  record User(int id, String login, String phone) {}
}
