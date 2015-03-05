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
package com.github.benmanes.caffeine.jcache.management;

import javax.cache.management.CacheStatisticsMXBean;

/**
 * The Caffeine JCache statistics bean.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class JCacheStatisticsMXBean implements CacheStatisticsMXBean {

  @Override
  public void clear() {}

  @Override
  public long getCacheHits() {
    return 0;
  }

  @Override
  public float getCacheHitPercentage() {
    return 0;
  }

  @Override
  public long getCacheMisses() {
    return 0;
  }

  @Override
  public float getCacheMissPercentage() {
    return 0;
  }

  @Override
  public long getCacheGets() {
    return 0;
  }

  @Override
  public long getCachePuts() {
    return 0;
  }

  @Override
  public long getCacheRemovals() {
    return 0;
  }

  @Override
  public long getCacheEvictions() {
    return 0;
  }

  @Override
  public float getAverageGetTime() {
    return 0;
  }

  @Override
  public float getAveragePutTime() {
    return 0;
  }

  @Override
  public float getAverageRemoveTime() {
    return 0;
  }
}
