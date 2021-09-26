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
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.truth.OptionalLongSubject.optionalLongs;
import static com.google.common.truth.StreamSubject.streams;
import static com.google.common.truth.Truth.assertAbout;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.RemovalListeners.ConsumingRemovalListener;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.base.CaseFormat;
import com.google.common.primitives.Ints;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Subject;
import com.google.errorprone.annotations.CheckReturnValue;

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

  /** Propositions for {@link CacheStats} subjects. */
  @CheckReturnValue
  public StatsSubject stats() {
    return actual.isRecordingStats()
        ? check("stats").about(STATS_FACTORY).that(actual)
        : ignoreCheck().about(STATS_FACTORY).that(actual);
  }

  /** Propositions for the removal listener's notifications. */
  @CheckReturnValue
  public ListenerSubject removalNotifications() {
    return check("context").about(REMOVAL_LISTENER_FACTORY).that(actual);
  }

  /** Propositions for the eviction listener's notifications. */
  @CheckReturnValue
  public ListenerSubject evictionNotifications() {
    return check("context").about(EVICTION_LISTENER_FACTORY).that(actual);
  }

  /** Propositions for the removal and eviction listener's notifications. */
  @CheckReturnValue
  public ListenerSubject notifications() {
    return check("context").about(LISTENERS_FACTORY).that(actual);
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

    public StatsSubject hits(long count) {
      return awaitStatistic("hitCount", CacheStats::hitCount, count);
    }

    public StatsSubject misses(long count) {
      return awaitStatistic("missCount", CacheStats::missCount, count);
    }

    public StatsSubject evictions(long count) {
      return awaitStatistic("evictionCount", CacheStats::evictionCount, count);
    }

    public StatsSubject evictionWeight(long count) {
      return awaitStatistic("evictionWeight", CacheStats::evictionWeight, count);
    }

    public StatsSubject success(long count) {
      return awaitStatistic("loadSuccessCount", CacheStats::loadSuccessCount, count);
    }

    public StatsSubject failures(long count) {
      return awaitStatistic("loadFailureCount", CacheStats::loadFailureCount, count);
    }

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

    private final Map<String, RemovalListener<Int, Int>> actual;
    private final boolean isDirect;

    private ListenerSubject(FailureMetadata metadata, CacheContext context,
        Map<String, RemovalListener<Int, Int>> subject) {
      super(metadata, subject);
      this.actual = subject;
      this.isDirect = (context.executorType() == CacheExecutor.DIRECT);
    }

    private static Factory<ListenerSubject, CacheContext> factoryOf(
        RemovalListenerType... removalListenerTypes) {
      return (metadata, context) -> {
        var subject = Stream.of(removalListenerTypes)
            .filter(listener -> listener.isConsumingListener(context))
            .collect(toMap(RemovalListenerType::label, listener -> listener.instance(context)));
        return new ListenerSubject(metadata, context, subject);
      };
    }

    /** Returns a subject with a qualifying removal cause. */
    @CheckReturnValue
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
        check(type).that(listener.removed()).hasSize(Ints.checkedCast(expectedSize));
      });
    }

    public void containsExactlyValues(Object... values) {
      awaitUntil((type, listener) -> {
        var stream = listener.removed().stream().map(RemovalNotification::getValue);
        check(type).about(streams()).that(stream).containsExactly(values);
      });
    }

    private <T> void awaitUntil(BiConsumer<String, ConsumingRemovalListener<Int, Int>> consumer) {
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

    public final class WithCause {
      private final RemovalCause cause;

      private WithCause(RemovalCause cause) {
        this.cause = requireNonNull(cause);
      }

      /** Fails if there are notifications with the given cause. */
      public void isEmpty() {
        awaitUntil((type, listener) -> {
          var notifications = listener.removed().stream()
              .filter(notification -> cause == notification.getCause());
          check(type).withMessage("%s(%s)", cause, listener.removed()).about(streams())
              .that(notifications).isEmpty();
        });
      }

      /** Fails if the number of notifications of the given cause does not have the given size. */
      public Exclusive hasSize(long expectedSize) {
        awaitUntil((type, listener) -> {
          var notifications = listener.removed().stream()
              .filter(notification -> cause == notification.getCause());
          check(type).withMessage("%s(%s)", cause, listener.removed()).about(streams())
              .that(notifications).hasSize(Ints.checkedCast(expectedSize));
        });
        return new Exclusive(expectedSize);
      }

      public Exclusive contains(Int key, Int value) {
        awaitUntil((type, listener) -> {
          check(type).withMessage("%s", cause)
              .that(listener.removed()).contains(new RemovalNotification<>(key, value, cause));
        });
        return new Exclusive(1);
      }

      public Exclusive contains(Entry<?, ?>... entries) {
        awaitUntil((type, listener) -> {
          var notifications = Stream.of(entries)
              .map(entry -> new RemovalNotification<>(entry.getKey(), entry.getValue(), cause))
              .collect(toList());
          check(type).withMessage("%s", cause)
              .that(listener.removed()).containsAtLeastElementsIn(notifications);
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
            check(type).that(listener.removed()).hasSize(Ints.checkedCast(expectedSize));
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
    private final String label;

    RemovalListenerType(Function<CacheContext, Listener> listener,
        Function<CacheContext, RemovalListener<Int, Int>> instance) {
      this.label = CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, name());
      this.listener = listener;
      this.instance = instance;
    }
    public String label() {
      return label;
    }
    public boolean isConsumingListener(CacheContext context) {
      return listener.apply(context) == Listener.CONSUMING;
    }
    public RemovalListener<Int, Int> instance(CacheContext context) {
      return instance.apply(context);
    }
  }
}
