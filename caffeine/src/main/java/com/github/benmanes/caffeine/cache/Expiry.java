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
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.cache.Caffeine.toNanosSaturated;
import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.time.Duration;
import java.util.function.BiFunction;

import org.jspecify.annotations.NullMarked;

import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * Calculates when cache entries expire. A single expiration time is retained so that the lifetime
 * of an entry may be extended or reduced by subsequent evaluations.
 * <p>
 * Usage example:
 * <pre>{@code
 *   LoadingCache<Key, Graph> cache = Caffeine.newBuilder()
 *       .expireAfter(Expiry.creating((Key key, Graph graph) ->
 *           Duration.between(Instant.now(), graph.createdOn().plusHours(5))))
 *       .build(key -> createExpensiveGraph(key));
 * }</pre>
 *
 * @param <K> the type of keys
 * @param <V> the type of values
 * @author ben.manes@gmail.com (Ben Manes)
 */
@NullMarked
public interface Expiry<K, V> {

  /**
   * Specifies that the entry should be automatically removed from the cache once the duration has
   * elapsed after the entry's creation. To indicate no expiration, an entry may be given an
   * excessively long period, such as {@link Long#MAX_VALUE}.
   * <p>
   * <b>Note:</b> The {@code currentTime} is supplied by the configured {@link Ticker} and by
   * default does not relate to system or wall-clock time. When calculating the duration based on a
   * timestamp, the current time should be obtained independently.
   *
   * @param key the key associated with this entry
   * @param value the value associated with this entry
   * @param currentTime the ticker's current time, in nanoseconds
   * @return the length of time before the entry expires, in nanoseconds
   */
  long expireAfterCreate(K key, V value, long currentTime);

  /**
   * Specifies that the entry should be automatically removed from the cache once the duration has
   * elapsed after the replacement of its value. To indicate no expiration, an entry may be given an
   * excessively long period, such as {@link Long#MAX_VALUE}. The {@code currentDuration} may be
   * returned to not modify the expiration time.
   * <p>
   * <b>Note:</b> The {@code currentTime} is supplied by the configured {@link Ticker} and by
   * default does not relate to system or wall-clock time. When calculating the duration based on a
   * timestamp, the current time should be obtained independently.
   *
   * @param key the key associated with this entry
   * @param value the new value associated with this entry
   * @param currentTime the ticker's current time, in nanoseconds
   * @param currentDuration the entry's current duration, in nanoseconds
   * @return the length of time before the entry expires, in nanoseconds
   */
  long expireAfterUpdate(K key, V value, long currentTime, long currentDuration);

  /**
   * Specifies that the entry should be automatically removed from the cache once the duration has
   * elapsed after its last read. To indicate no expiration, an entry may be given an excessively
   * long period, such as {@link Long#MAX_VALUE}. The {@code currentDuration} may be returned to not
   * modify the expiration time.
   * <p>
   * <b>Note:</b> The {@code currentTime} is supplied by the configured {@link Ticker} and by
   * default does not relate to system or wall-clock time. When calculating the duration based on a
   * timestamp, the current time should be obtained independently.
   *
   * @param key the key associated with this entry
   * @param value the value associated with this entry
   * @param currentTime the ticker's current time, in nanoseconds
   * @param currentDuration the entry's current duration, in nanoseconds
   * @return the length of time before the entry expires, in nanoseconds
   */
  long expireAfterRead(K key, V value, long currentTime, long currentDuration);

  /**
   * Returns an {@code Expiry} that specifies that the entry should be automatically removed from
   * the cache once the duration has elapsed after the entry's creation. The expiration time is
   * not modified when the entry is updated or read.
   *
   * <pre>{@code
   * Expiry<Key, Graph> expiry = Expiry.creating((key, graph) ->
   *     Duration.between(Instant.now(), graph.createdOn().plusHours(5)));
   * }</pre>
   *
   * @param <K> the key type
   * @param <V> the value type
   * @param function the function used to calculate the length of time after an entry is created
   *        before it should be automatically removed
   * @return an {@code Expiry} instance with the specified expiry function
   */
  static <K, V> Expiry<K, V> creating(BiFunction<K, V, Duration> function) {
    return new ExpiryAfterCreate<>(function);
  }

  /**
   * Returns an {@code Expiry} that specifies that the entry should be automatically removed from
   * the cache once the duration has elapsed after the entry's creation or replacement of its value.
   * The expiration time is not modified when the entry is read.
   *
   * <pre>{@code
   * Expiry<Key, Graph> expiry = Expiry.writing((key, graph) ->
   *     Duration.between(Instant.now(), graph.modifiedOn().plusHours(5)));
   * }</pre>
   *
   * @param <K> the key type
   * @param <V> the value type
   * @param function the function used to calculate the length of time after an entry is created
   *        or updated that it should be automatically removed
   * @return an {@code Expiry} instance with the specified expiry function
   */
  static <K, V> Expiry<K, V> writing(BiFunction<K, V, Duration> function) {
    return new ExpiryAfterWrite<>(function);
  }

  /**
   * Returns an {@code Expiry} that specifies that the entry should be automatically removed from
   * the cache once the duration has elapsed after the entry's creation, replacement of its value,
   * or after it was last read.
   *
   * <pre>{@code
   * Expiry<Key, Graph> expiry = Expiry.accessing((key, graph) ->
   *     graph.isDirected() ? Duration.ofHours(1) : Duration.ofHours(3));
   * }</pre>
   *
   * @param <K> the key type
   * @param <V> the value type
   * @param function the function used to calculate the length of time after an entry last accessed
   *        that it should be automatically removed
   * @return an {@code Expiry} instance with the specified expiry function
   */
  static <K, V> Expiry<K, V> accessing(BiFunction<K, V, Duration> function) {
    return new ExpiryAfterAccess<>(function);
  }
}

final class ExpiryAfterCreate<K, V> implements Expiry<K, V>, Serializable {
  private static final long serialVersionUID = 1L;

  @SuppressWarnings("serial")
  final BiFunction<K, V, Duration> function;

  public ExpiryAfterCreate(BiFunction<K, V, Duration> calculator) {
    this.function = requireNonNull(calculator);
  }
  @Override public long expireAfterCreate(K key, V value, long currentTime) {
    return toNanosSaturated(function.apply(key, value));
  }
  @CanIgnoreReturnValue
  @Override public long expireAfterUpdate(K key, V value, long currentTime, long currentDuration) {
    return currentDuration;
  }
  @CanIgnoreReturnValue
  @Override public long expireAfterRead(K key, V value, long currentTime, long currentDuration) {
    return currentDuration;
  }
}

final class ExpiryAfterWrite<K, V> implements Expiry<K, V>, Serializable {
  private static final long serialVersionUID = 1L;

  @SuppressWarnings("serial")
  final BiFunction<K, V, Duration> function;

  public ExpiryAfterWrite(BiFunction<K, V, Duration> calculator) {
    this.function = requireNonNull(calculator);
  }
  @Override public long expireAfterCreate(K key, V value, long currentTime) {
    return toNanosSaturated(function.apply(key, value));
  }
  @Override public long expireAfterUpdate(K key, V value, long currentTime, long currentDuration) {
    return toNanosSaturated(function.apply(key, value));
  }
  @CanIgnoreReturnValue
  @Override public long expireAfterRead(K key, V value, long currentTime, long currentDuration) {
    return currentDuration;
  }
}

final class ExpiryAfterAccess<K, V> implements Expiry<K, V>, Serializable {
  private static final long serialVersionUID = 1L;

  @SuppressWarnings("serial")
  final BiFunction<K, V, Duration> function;

  public ExpiryAfterAccess(BiFunction<K, V, Duration> calculator) {
    this.function = requireNonNull(calculator);
  }
  @Override public long expireAfterCreate(K key, V value, long currentTime) {
    return toNanosSaturated(function.apply(key, value));
  }
  @Override public long expireAfterUpdate(K key, V value, long currentTime, long currentDuration) {
    return toNanosSaturated(function.apply(key, value));
  }
  @Override public long expireAfterRead(K key, V value, long currentTime, long currentDuration) {
    return toNanosSaturated(function.apply(key, value));
  }
}
