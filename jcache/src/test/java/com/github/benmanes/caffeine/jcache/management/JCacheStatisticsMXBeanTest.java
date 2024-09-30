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

import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("MemberName")
public final class JCacheStatisticsMXBeanTest {

  @Test
  public void clear() {
    var stats = new JCacheStatisticsMXBean();
    stats.recordHits(1);
    stats.recordMisses(1);
    stats.recordPuts(1);
    stats.recordRemovals(1);
    stats.recordEvictions(1);
    stats.recordGetTime(1);
    stats.recordPutTime(1);
    stats.recordRemoveTime(1);

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
}
