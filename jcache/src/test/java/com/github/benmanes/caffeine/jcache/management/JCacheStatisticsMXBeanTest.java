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
package com.github.benmanes.caffeine.jcache.management;

import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("IdentifierName")
final class JCacheStatisticsMXBeanTest {

  @Test
  void clear() {
    var stats = new JCacheStatisticsMXBean();
    stats.enable(true); // record* no-ops while disabled (the default), which is vacuous here
    stats.recordHits(1);
    stats.recordMisses(1);
    stats.recordPuts(1);
    stats.recordRemovals(1);
    stats.recordEvictions(1);
    // average() truncates nanos to micros, so record at least a microsecond to be non-zero
    stats.recordGetTime(TimeUnit.MILLISECONDS.toNanos(1));
    stats.recordPutTime(TimeUnit.MILLISECONDS.toNanos(1));
    stats.recordRemoveTime(TimeUnit.MILLISECONDS.toNanos(1));

    // Prove the counters are non-zero first, else clear()'s reset is asserted vacuously
    assertThat(stats.getCacheHits()).isEqualTo(1L);
    assertThat(stats.getCacheMisses()).isEqualTo(1L);
    assertThat(stats.getCachePuts()).isEqualTo(1L);
    assertThat(stats.getCacheRemovals()).isEqualTo(1L);
    assertThat(stats.getCacheEvictions()).isEqualTo(1L);
    assertThat(stats.getAverageGetTime()).isGreaterThan(0F);
    assertThat(stats.getAveragePutTime()).isGreaterThan(0F);
    assertThat(stats.getAverageRemoveTime()).isGreaterThan(0F);

    stats.clear();
    assertThat(stats.getCacheHits()).isEqualTo(0L);
    assertThat(stats.getCacheMisses()).isEqualTo(0L);
    assertThat(stats.getCachePuts()).isEqualTo(0L);
    assertThat(stats.getCacheRemovals()).isEqualTo(0L);
    assertThat(stats.getCacheEvictions()).isEqualTo(0L);
    assertThat(stats.getAverageGetTime()).isEqualTo(0F);
    assertThat(stats.getAveragePutTime()).isEqualTo(0F);
    assertThat(stats.getAverageRemoveTime()).isEqualTo(0F);
  }

  @Test
  void clear_thenRepopulated_freshAverages() {
    // A zero average after clear() is also produced by a regression that resets only the
    // counters (or only the timers): both operands must reach zero together. Repopulating with
    // distinct, unequal counts and time totals per operation discriminates a partial reset, since
    // a leftover counter or timer from before the clear would not divide out to these means.
    var stats = new JCacheStatisticsMXBean();
    stats.enable(true);
    stats.recordHits(1);
    stats.recordPuts(1);
    stats.recordRemovals(1);
    stats.recordGetTime(TimeUnit.MILLISECONDS.toNanos(5));
    stats.recordPutTime(TimeUnit.MILLISECONDS.toNanos(5));
    stats.recordRemoveTime(TimeUnit.MILLISECONDS.toNanos(5));

    stats.clear();

    stats.recordHits(2);
    stats.recordMisses(1); // 3 gets
    stats.recordPuts(2);
    stats.recordRemovals(1);
    stats.recordGetTime(TimeUnit.MICROSECONDS.toNanos(9)); // 9 us / 3 gets = 3 us
    stats.recordPutTime(TimeUnit.MICROSECONDS.toNanos(8)); // 8 us / 2 puts = 4 us
    stats.recordRemoveTime(TimeUnit.MICROSECONDS.toNanos(7)); // 7 us / 1 removal = 7 us

    assertThat(stats.getAverageGetTime()).isEqualTo(3F);
    assertThat(stats.getAveragePutTime()).isEqualTo(4F);
    assertThat(stats.getAverageRemoveTime()).isEqualTo(7F);
  }

  @Test
  void record_disabledOrZero_isNoOp() {
    var stats = new JCacheStatisticsMXBean();

    // Disabled (the default): every record* short-circuits on the `enabled` guard. The cache only
    // calls the guarded record* methods under an `if (statsEnabled)` check, so this branch is
    // reachable only by a direct call.
    stats.recordPuts(1);
    stats.recordGetTime(1);
    stats.recordPutTime(1);
    stats.recordRemoveTime(1);
    assertThat(stats.getCachePuts()).isEqualTo(0L);
    assertThat(stats.getAverageGetTime()).isEqualTo(0F);

    // Enabled but zero: the `count`/`durationNanos != 0` guard still short-circuits the add
    stats.enable(true);
    stats.recordPuts(0);
    stats.recordGetTime(0);
    stats.recordPutTime(0);
    stats.recordRemoveTime(0);
    assertThat(stats.getCachePuts()).isEqualTo(0L);
    assertThat(stats.getAverageGetTime()).isEqualTo(0F);
    assertThat(stats.getAveragePutTime()).isEqualTo(0F);
    assertThat(stats.getAverageRemoveTime()).isEqualTo(0F);
  }

  @Test
  void loadTime_untracked_isIgnored() {
    var stats = new JCacheStatisticsMXBean();

    // The loader adapter records only after isRecordingLoadTime() and the read-through proxy ends
    // only what it began, so an untracked record or end is reachable only by a direct call
    stats.recordLoadTime(1);
    assertThat(stats.isRecordingLoadTime()).isFalse();
    assertThat(stats.endLoadTime()).isEqualTo(0L);

    stats.beginLoadTime();
    stats.recordLoadTime(1);
    assertThat(stats.endLoadTime()).isEqualTo(1L);
    assertThat(stats.isRecordingLoadTime()).isFalse();
  }

  @Test
  void getCacheGets_overflow_saturates() {
    var stats = new JCacheStatisticsMXBean();
    stats.enable(true);
    stats.recordHits(Long.MAX_VALUE);
    stats.recordMisses(1);

    assertThat(stats.getCacheGets()).isEqualTo(Long.MAX_VALUE);
    assertThat(stats.getCacheHitPercentage()).isEqualTo(100F);
  }
}
