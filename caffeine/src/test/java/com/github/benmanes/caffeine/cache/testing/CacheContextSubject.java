/*
 * Copyright 2021 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.testing;

import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.ListenerSubject.EVICTION_LISTENER_FACTORY;
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.ListenerSubject.LISTENERS_FACTORY;
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.ListenerSubject.REMOVAL_LISTENER_FACTORY;
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.StatsSubject.STATS_FACTORY;
import static com.github.benmanes.caffeine.cache.testing.CacheSubject.cache;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableListMultimap.toImmutableListMultimap;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableMultiset.toImmutableMultiset;
import static com.google.common.truth.OptionalLongSubject.optionalLongs;
import static com.google.common.truth.StreamSubject.streams;
import static com.google.common.truth.Truth.assertAbout;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.function.Function.identity;

import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Policy.CacheEntry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.RemovalListeners.ConsumingRemovalListener;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Multimaps;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.StandardSubjectBuilder;
import com.google.common.truth.Subject;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * Propositions for {@link CacheContext} subjects.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheContextSubject extends Subject {
  private final CacheContext actual;

  CacheContextSubject(FailureMetadata metadata, CacheContext subject) {
    super(metadata, subject);
    this.actual = subject;
  }

  public static Factory<CacheContextSubject, CacheContext> context() {
    return CacheContextSubject::new;
  }

  public static CacheContextSubject assertThat(CacheContext actual) {
    return assertAbout(context()).that(actual);
  }

  /** Fails if the cache does not have the given weighted size. */
  public void hasWeightedSize(long expectedSize) {
    checkArgument(expectedSize >= 0, "expectedSize (%s) must be >= 0", expectedSize);
    actual.cache().policy().eviction().ifPresentOrElse(policy -> {
      check("weightedSize()").about(optionalLongs())
          .that(policy.weightedSize()).hasValue(expectedSize);
    }, () -> {
      long weight = actual.cache().asMap().entrySet().stream()
          .mapToLong(entry -> actual.weigher().weigh(entry.getKey(), entry.getValue()))
          .sum();
      check("weight").that(weight).isEqualTo(expectedSize);
    });
  }

  /** Fails if the cache does not have less than the given weighted size. */
  public void hasWeightedSizeLessThan(long other) {
    checkArgument(other >= 0, "other (%s) must be >= 0", other);
    actual.cache().policy().eviction().ifPresentOrElse(policy -> {
      check("weightedSize()").about(optionalLongs()).that(policy.weightedSize()).isPresent();
      check("weightedSize()").that(policy.weightedSize().orElseThrow()).isLessThan(other);
    }, () -> {
      long weight = actual.cache().asMap().entrySet().stream()
          .mapToLong(entry -> actual.weigher().weigh(entry.getKey(), entry.getValue()))
          .sum();
      check("weight").that(weight).isLessThan(other);
    });
  }

  /** Fails if the cache does not have the given entry and metadata. */
  public void containsEntry(CacheEntry<Int, Int> entry) {
    checkBasic(entry);
    checkWeight(entry);
    checkExpiresAt(entry);
    checkRefreshableAt(entry);
  }

  /** Propositions for {@link CacheStats} subjects. */
  public StatsSubject stats() {
    return actual.isRecordingStats()
        ? check("stats").about(STATS_FACTORY).that(actual)
        : ignoreCheck().about(STATS_FACTORY).that(actual);
  }

  /** Propositions for the removal listener's notifications. */
  public ListenerSubject removalNotifications() {
    return check("context").about(REMOVAL_LISTENER_FACTORY).that(actual);
  }

  /** Propositions for the eviction listener's notifications. */
  public ListenerSubject evictionNotifications() {
    return check("context").about(EVICTION_LISTENER_FACTORY).that(actual);
  }

  /** Propositions for the removal and eviction listener's notifications. */
  public ListenerSubject notifications() {
    return check("context").about(LISTENERS_FACTORY).that(actual);
  }

  private void checkBasic(CacheEntry<Int, Int> entry) {
    check("entry").about(cache()).that(actual.cache())
        .containsEntry(entry.getKey(), entry.getValue());
    check("snapshotAt").that(entry.snapshotAt())
        .isEqualTo((actual.expires() || actual.refreshes()) ? actual.ticker().read() : 0L);
    try {
      entry.setValue(entry.getValue());
      failWithActual("setValue", entry);
    } catch (UnsupportedOperationException expected) { /* ignored */ }
  }

  private void checkWeight(CacheEntry<Int, Int> entry) {
    @SuppressWarnings("unchecked")
    var cache = (Cache<Int, Int>) actual.cache();
    cache.policy().eviction().ifPresent(policy -> {
      check("weight").that(entry.weight()).isEqualTo(policy.weightOf(entry.getKey()).orElse(1));
    });
  }

  private void checkExpiresAt(CacheEntry<Int, Int> entry) {
    @SuppressWarnings("unchecked")
    var cache = (Cache<Int, Int>) actual.cache();
    long expiresAt = entry.expiresAt();
    long now = actual.ticker().read();

    if (!actual.expires()) {
      check("expiresAt").that(expiresAt).isEqualTo(entry.snapshotAt() + Long.MAX_VALUE);
    }
    cache.policy().expireAfterAccess().ifPresent(policy -> {
      var duration = policy.getExpiresAfter().minus(
          policy.ageOf(entry.getKey()).orElseThrow());
      check("expiresAccessAt").that(NANOSECONDS.toSeconds(expiresAt))
          .isEqualTo(NANOSECONDS.toSeconds(now + duration.toNanos()));
      check("expiresAccessAfter")
          .that(entry.expiresAfter().toSeconds()).isEqualTo(duration.toSeconds());
    });
    cache.policy().expireAfterWrite().ifPresent(policy -> {
      var duration = policy.getExpiresAfter().minus(
          policy.ageOf(entry.getKey()).orElseThrow());
      check("expiresWriteAt").that(NANOSECONDS.toSeconds(expiresAt))
          .isEqualTo(NANOSECONDS.toSeconds(now + duration.toNanos()));
      check("expiresWriteAfter")
          .that(entry.expiresAfter().toSeconds()).isEqualTo(duration.toSeconds());
    });
    cache.policy().expireVariably().ifPresent(policy -> {
      long expected = now + policy.getExpiresAfter(entry.getKey(), NANOSECONDS).orElseThrow();
      check("expiresVariablyAt").that(expiresAt).isEqualTo(expected);
      check("expiresVariablyAfter").that(entry.expiresAfter())
          .isEqualTo(policy.getExpiresAfter(entry.getKey()).orElseThrow());
    });
  }

  private void checkRefreshableAt(CacheEntry<Int, Int> entry) {
    @SuppressWarnings("unchecked")
    var cache = (Cache<Int, Int>) actual.cache();
    long refreshableAt = entry.refreshableAt();
    long now = actual.ticker().read();

    if (!actual.refreshes()) {
      check("refreshableAt").that(refreshableAt).isEqualTo(entry.snapshotAt() + Long.MAX_VALUE);
    }
    cache.policy().refreshAfterWrite().ifPresent(policy -> {
      var duration = policy.getRefreshesAfter().minus(
          policy.ageOf(entry.getKey()).orElseThrow());
      check("refreshableAt").that(NANOSECONDS.toSeconds(refreshableAt))
          .isEqualTo(NANOSECONDS.toSeconds(now + duration.toNanos()));
      check("refreshableAfter")
          .that(entry.refreshableAfter().toSeconds()).isEqualTo(duration.toSeconds());
    });
  }

  public static final class StatsSubject extends Subject {
    static final Factory<StatsSubject, CacheContext> STATS_FACTORY = StatsSubject::new;

    private final CacheContext actual;
    private final boolean isDirect;

    private StatsSubject(FailureMetadata metadata, CacheContext context) {
      super(metadata, context);
      this.actual = context;
      this.isDirect = !context.isRecordingStats()
          || (context.executorType() == CacheExecutor.DIRECT);
    }

    @CanIgnoreReturnValue
    public StatsSubject hits(long count) {
      return awaitStatistic("hitCount", CacheStats::hitCount, count);
    }

    @CanIgnoreReturnValue
    public StatsSubject misses(long count) {
      return awaitStatistic("missCount", CacheStats::missCount, count);
    }

    @CanIgnoreReturnValue
    public StatsSubject evictions(long count) {
      return awaitStatistic("evictionCount", CacheStats::evictionCount, count);
    }

    @CanIgnoreReturnValue
    public StatsSubject evictionWeight(long count) {
      return awaitStatistic("evictionWeight", CacheStats::evictionWeight, count);
    }

    @CanIgnoreReturnValue
    public StatsSubject success(long count) {
      return awaitStatistic("loadSuccessCount", CacheStats::loadSuccessCount, count);
    }

    @CanIgnoreReturnValue
    public StatsSubject failures(long count) {
      return awaitStatistic("loadFailureCount", CacheStats::loadFailureCount, count);
    }

    @CanIgnoreReturnValue
    private StatsSubject awaitStatistic(String label,
        ToLongFunction<CacheStats> supplier, long expectedValue) {
      if (isDirect) {
        checkStatistic(label, supplier, expectedValue);
      } else if (supplier.applyAsLong(actual.stats()) != expectedValue) {
        await().pollInSameThread().untilAsserted(() ->
            checkStatistic(label, supplier, expectedValue));
      }
      return this;
    }

    private void checkStatistic(String label,
        ToLongFunction<CacheStats> supplier, long expectedValue) {
      var stats = actual.stats();
      check(label).withMessage("%s", stats)
          .that(supplier.applyAsLong(stats)).isEqualTo(expectedValue);
    }
  }

  public static final class ListenerSubject extends Subject {
    static final Factory<ListenerSubject, CacheContext> REMOVAL_LISTENER_FACTORY =
        factoryOf(RemovalListenerType.REMOVAL_LISTENER);
    static final Factory<ListenerSubject, CacheContext> EVICTION_LISTENER_FACTORY =
        factoryOf(RemovalListenerType.EVICTION_LISTENER);
    static final Factory<ListenerSubject, CacheContext> LISTENERS_FACTORY =
        factoryOf(RemovalListenerType.values());

    private final Map<RemovalListenerType, RemovalListener<Int, Int>> actual;
    private final boolean isDirect;

    private ListenerSubject(FailureMetadata metadata, CacheContext context,
        Map<RemovalListenerType, RemovalListener<Int, Int>> subject) {
      super(metadata, subject);
      this.actual = subject;
      this.isDirect = (context.executorType() == CacheExecutor.DIRECT);
    }

    private static Factory<ListenerSubject, CacheContext> factoryOf(
        RemovalListenerType... removalListenerTypes) {
      return (metadata, context) -> {
        var subject = Arrays.stream(removalListenerTypes)
            .filter(listener -> listener.isConsumingListener(context))
            .collect(toImmutableMap(identity(), listener -> listener.instance(context)));
        return new ListenerSubject(metadata, context, subject);
      };
    }

    /** Returns a subject with a qualifying removal cause. */
    public WithCause withCause(RemovalCause cause) {
      return new WithCause(cause);
    }

    /** Fails if there were notifications. */
    public void isEmpty() {
      awaitUntil((type, listener) -> check(type).that(listener.removed()).isEmpty());
    }

    /** Fails if the number of notifications does not have the given size. */
    public void hasSize(long expectedSize) {
      awaitUntil((type, listener) -> {
        check(type).that(listener.removed()).hasSize(Math.toIntExact(expectedSize));
      });
    }

    /** Fails if the notifications do not have the given values. */
    public void containsExactlyValues(Object... values) {
      awaitUntil((type, listener) -> {
        var stream = listener.removed().stream().map(RemovalNotification::getValue);
        check(type).about(streams()).that(stream).containsExactly(values);
      });
    }

    public void hasNoEvictions() {
      awaitUntil((type, listener) -> {
        var stream = listener.removed().stream().filter(entry -> entry.getCause().wasEvicted());
        check(type).about(streams()).that(stream).isEmpty();
      });
    }

    private void awaitUntil(
        BiConsumer<RemovalListenerType, ConsumingRemovalListener<Int, Int>> consumer) {
      actual.forEach((type, listener) -> {
        if (!(listener instanceof ConsumingRemovalListener<?, ?>)) {
          return;
        }
        var consuming = (ConsumingRemovalListener<Int, Int>) listener;
        if (isDirect) {
          consumer.accept(type, consuming);
        } else {
          await().untilAsserted(() -> consumer.accept(type, consuming));
        }
      });
    }

    private StandardSubjectBuilder check(RemovalListenerType type) {
      return check(type.toString());
    }

    public final class WithCause {
      private final RemovalCause cause;

      private WithCause(RemovalCause cause) {
        this.cause = requireNonNull(cause);
      }

      @CanIgnoreReturnValue
      public Exclusive contains(Int key, Int value) {
        return contains(new SimpleEntry<>(key, value));
      }

      @CanIgnoreReturnValue
      public Exclusive contains(Map<Int, Int> map) {
        return contains(map.entrySet().toArray(Map.Entry[]::new));
      }

      @CanIgnoreReturnValue
      public Exclusive contains(List<Entry<Int, Int>> entries) {
        return contains(entries.toArray(Map.Entry[]::new));
      }

      @CanIgnoreReturnValue
      public Exclusive contains(Entry<?, ?>... entries) {
        awaitUntil((type, listener) -> {
          var notifications = Arrays.stream(entries)
              .map(entry -> new RemovalNotification<>(entry.getKey(), entry.getValue(), cause))
              .collect(toImmutableListMultimap(RemovalNotification::getCause, identity()));
          var actual = Multimaps.index(listener.removed(), RemovalNotification::getCause);
          check(type).that(actual).containsAtLeastEntriesIn(notifications);
        });
        return new Exclusive(entries.length);
      }

      public final class Exclusive {
        private final long expectedSize;

        private Exclusive(long expectedSize) {
          this.expectedSize = expectedSize;
        }

        /** Fails if there are notifications with a different cause. */
        public void exclusively() {
          awaitUntil((type, listener) -> {
            var causes = listener.removed().stream()
                .map(RemovalNotification::getCause)
                .collect(toImmutableMultiset());
            check(type).that(causes).isEqualTo(ImmutableMultiset.builder()
                .addCopies(cause, Math.toIntExact(expectedSize)).build());
          });
        }
      }
    }
  }

  @SuppressWarnings("ImmutableEnumChecker")
  private enum RemovalListenerType {
    REMOVAL_LISTENER(CacheContext::removalListenerType, CacheContext::removalListener),
    EVICTION_LISTENER(CacheContext::evictionListenerType, CacheContext::evictionListener);

    private final Function<CacheContext, RemovalListener<Int, Int>> instance;
    private final Function<CacheContext, Listener> listener;

    RemovalListenerType(Function<CacheContext, Listener> listener,
        Function<CacheContext, RemovalListener<Int, Int>> instance) {
      this.listener = listener;
      this.instance = instance;
    }
    public boolean isConsumingListener(CacheContext context) {
      return listener.apply(context) == Listener.CONSUMING;
    }
    public RemovalListener<Int, Int> instance(CacheContext context) {
      return instance.apply(context);
    }
    @Override
    public String toString() {
      return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, name());
    }
  }
}
