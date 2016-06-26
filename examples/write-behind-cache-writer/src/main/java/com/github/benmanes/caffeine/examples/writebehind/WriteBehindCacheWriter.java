package com.github.benmanes.caffeine.examples.writebehind;

import com.github.benmanes.caffeine.cache.CacheWriter;
import com.github.benmanes.caffeine.cache.RemovalCause;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import rx.functions.Action1;
import rx.subjects.PublishSubject;

/**
 * This class implements the {@link CacheWriter} interface to allow to have "write-behind"
 * semantics. The passed in writeAction will only be called every 'bufferTime' time with a Map
 * containing the keys and the values that have been updated in the cache each time.
 *
 * If a key is updated multiple times during that period, then the 'binaryOperator' has to decide
 * which value should be taken.
 *
 * An example usage of this class is keeping track of users and their activity on a website. You
 * don't want to update the database each time any of your users do something on your website. It is
 * better to batch the updates every x seconds and just write the most recent time in the database.
 *
 * @param <K> the type of the key in the cache
 * @param <V> the type of the value in the cache
 */
public class WriteBehindCacheWriter<K, V> implements CacheWriter<K, V> {
    private final PublishSubject<ImmutablePair<K, V>> subject;
    private final Function<ImmutablePair<K, V>, K> keyMapper = ImmutablePair::getKey;
    private final Function<ImmutablePair<K, V>, V> valueMapper = ImmutablePair::getValue;

    /**
     * Constructor
     *
     * @param bufferTime     the duration that the calls to the cache should be buffered before
     *                       calling the <code>writeAction</code>
     * @param binaryOperator decides which value to take in case a key was updated multiple times
     * @param writeAction    callback to do the writing to the database or repository
     */
    public WriteBehindCacheWriter(Duration bufferTime,
                                  BinaryOperator<V> binaryOperator,
                                  Consumer<Map<K, V>> writeAction) {
        this.subject = PublishSubject.create();

        subject.buffer(bufferTime.toNanos(), TimeUnit.NANOSECONDS)
                .map(entries -> entries.stream().collect(Collectors.toMap(keyMapper,
                        valueMapper,
                        binaryOperator)))
                .subscribe(writeAction::accept);
    }

    @Override
    public void write(K key, V value) {
        subject.onNext(new ImmutablePair<>(key, value));
    }

    @Override
    public void delete(K key, V value, RemovalCause removalCause) {

    }
}

