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

import java.io.Closeable;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;

import com.google.errorprone.annotations.CanIgnoreReturnValue;

import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.subjects.PublishSubject;
import io.reactivex.rxjava3.subjects.Subject;

/**
 * This class allows a cache to have write-behind semantics. The passed in {@code writeAction}
 * will only be called every {@code bufferTime} time with a {@linkplain} Map} containing the keys
 * and the values that have been updated in the cache each time.
 * <p>
 * If a key is updated multiple times during that period then the {@code binaryOperator} has to
 * decide which value should be taken.
 * <p>
 * An example usage of this class is keeping track of users and their activity on a web site. You
 * don't want to update the database each time any of your users does something. It is better to
 * batch the updates every x seconds and just write the most recent time in the database.
 *
 * @param <K> the type of the key in the cache
 * @param <V> the type of the value in the cache
 * @author wim.deblauwe@gmail.com (Wim Deblauwe)
 */
public final class WriteBehindCacheWriter<K, V> implements BiConsumer<K, V>, Closeable {
  final Subject<Entry<K, V>> subject;
  final Disposable subscription;

  private WriteBehindCacheWriter(Builder<K, V> builder) {
    subject = PublishSubject.<Entry<K, V>>create().toSerialized();
    subscription = subject.buffer(builder.bufferTimeNanos, TimeUnit.NANOSECONDS)
        .map(entries -> entries.stream().collect(
            toMap(Entry::getKey, Entry::getValue, builder.coalescer)))
        .subscribe(builder.writeAction::accept);
  }

  @Override public void accept(K key, V value) {
    subject.onNext(Map.entry(key, value));
  }

  @Override public void close() {
    subscription.dispose();
  }

  public static final class Builder<K, V> {
    private Consumer<Map<K, V>> writeAction;
    private BinaryOperator<V> coalescer;
    private long bufferTimeNanos;

    /**
     * The duration that the calls to the cache should be buffered before calling the
     * {@code writeAction}.
     */
    @CanIgnoreReturnValue
    public Builder<K, V> bufferTime(long duration, TimeUnit unit) {
      this.bufferTimeNanos = TimeUnit.NANOSECONDS.convert(duration, unit);
      return this;
    }

    /** The callback to perform the writing to the database or repository. */
    @CanIgnoreReturnValue
    public Builder<K, V> writeAction(Consumer<Map<K, V>> writeAction) {
      this.writeAction = requireNonNull(writeAction);
      return this;
    }

    /** The action that decides which value to take in case a key was updated multiple times. */
    @CanIgnoreReturnValue
    public Builder<K, V> coalesce(BinaryOperator<V> coalescer) {
      this.coalescer = requireNonNull(coalescer);
      return this;
    }

    /** Returns a writer that batches changes to the system of record. */
    public WriteBehindCacheWriter<K, V> build() {
      requireNonNull(coalescer);
      requireNonNull(writeAction);
      requireNonNull(bufferTimeNanos);
      return new WriteBehindCacheWriter<>(this);
    }
  }
}
