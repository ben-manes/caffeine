/*
 * Copyright 2019 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.membership.bloom;

import static com.google.common.base.Preconditions.checkState;

import org.fastfilter.Filter;
import org.fastfilter.FilterType;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.membership.Membership;
import com.google.common.base.CaseFormat;
import com.typesafe.config.Config;

/**
 * An adapter to <a href="https://github.com/FastFilter/fastfilter_java">FastFilter</a>
 * implementations.
 *
 * @author ben@withvector.com (Ben Manes)
 */
public final class FastFilter implements Membership {
  private final FilterType filterType;
  private final int bitsPerKey;
  private final long[] keys;

  private Filter filter;

  public FastFilter(Config config) {
    FastFilterSettings settings = new FastFilterSettings(config);
    keys = new long[(int) settings.membership().expectedInsertions()];
    filterType = settings.filterType();
    bitsPerKey = settings.bitsPerKey();
    reset();
  }

  @Override
  public boolean mightContain(long e) {
    return filter.mayContain(e);
  }

  @Override
  public void clear() {
    reset();
  }

  @Override
  public boolean put(long e) {
    if (filter.mayContain(e)) {
      return false;
    }
    filter.add(e);
    return true;
  }

  private void reset() {
    filter = filterType.construct(keys, bitsPerKey);
    checkState(filter.supportsAdd(), "Filter must support additions");
  }

  static final class FastFilterSettings extends BasicSettings {
    public FastFilterSettings(Config config) {
      super(config);
    }
    public FilterType filterType() {
      String type = config().getString("membership.fast-filter.type");
      return FilterType.valueOf(CaseFormat.LOWER_HYPHEN.to(CaseFormat.UPPER_UNDERSCORE, type));
    }
    public int bitsPerKey() {
      return config().getInt("membership.fast-filter.bits-per-key");
    }
  }
}
