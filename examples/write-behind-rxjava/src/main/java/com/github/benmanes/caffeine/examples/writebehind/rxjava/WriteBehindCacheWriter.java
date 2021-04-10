/*
 * Copyright 2016 Wim Deblauwe. All Rights Reserved.
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
package com.github.benmanes.caffeine.examples.writebehind.rxjava;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;

import io.reactivex.subjects.PublishSubject;

/**
 * This class allows a cache to have "write-behind" semantics. The passed in writeAction will only
 * be called every 'bufferTime' time with a Map containing the keys and the values that have been
 * updated in the cache each time.
 * <p>
 * If a key is updated multiple times during that period then the 'binaryOperator' has to decide
 * which value should be taken.
 * <p>
 * An example usage of this class is keeping track of users and their activity on a web site. You
 * don't want to update the database each time any of your users does something. It is better to
 * batch the updates every x seconds and just write the most recent time in the database.
 *
 * @param <K> the type of the key in the cache
 * @param <V> the type of the value in the cache
 * @author wim.deblauwe@gmail.com (Wim Deblauwe)
 */
public final class WriteBehindCacheWriter<K, V> {
  private final PublishSubject<Entry<K, V>> subject;

  private WriteBehindCacheWriter(Builder<K, V> builder) {
    subject = PublishSubject.create();
    subject.buffer(builder.bufferTimeNanos, TimeUnit.NANOSECONDS)
        .map(entries -> entries.stream().collect(
            toMap(Entry::getKey, Entry::getValue, builder.coalescer)))
        .subscribe(builder.writeAction::accept);
  }

  public void write(K key, V value) {
    subject.onNext(new SimpleImmutableEntry<>(key, value));
  }

  public static final class Builder<K, V> {
    private Consumer<Map<K, V>> writeAction;
    private BinaryOperator<V> coalescer;
    private long bufferTimeNanos;

    /**
     * The duration that the calls to the cache should be buffered before calling the
     * <code>writeAction</code>.
     */
    public Builder<K, V> bufferTime(long duration, TimeUnit unit) {
      this.bufferTimeNanos = TimeUnit.NANOSECONDS.convert(duration, unit);
      return this;
    }

    /** The callback to perform the writing to the database or repository. */
    public Builder<K, V> writeAction(Consumer<Map<K, V>> writeAction) {
      this.writeAction = requireNonNull(writeAction);
      return this;
    }

    /** The action that decides which value to take in case a key was updated multiple times. */
    public Builder<K, V> coalesce(BinaryOperator<V> coalescer) {
      this.coalescer = requireNonNull(coalescer);
      return this;
    }

    /** Returns a {@link CacheWriter} that batches writes to the system of record. */
    public WriteBehindCacheWriter<K, V> build() {
      return new WriteBehindCacheWriter<>(this);
    }
  }
}
