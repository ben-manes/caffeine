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
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.testing.IsEmptyMap.emptyMap;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;

import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.github.benmanes.caffeine.testing.DescriptionBuilder;

/**
 * A matcher that evaluates a {@link UnboundedLocalCache} to determine if it is in a valid state.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsValidUnboundedLocalCache<K, V>
    extends TypeSafeDiagnosingMatcher<UnboundedLocalCache<K, V>> {
  DescriptionBuilder desc;

  @Override
  public void describeTo(Description description) {
    description.appendText("valid unbounded cache");
    if (desc.getDescription() != description) {
      description.appendText(desc.getDescription().toString());
    }
  }

  @Override
  protected boolean matchesSafely(UnboundedLocalCache<K, V> map, Description description) {
    desc = new DescriptionBuilder(description);
    checkMap(map, desc);
    return desc.matches();
  }

  private void checkMap(UnboundedLocalCache<K, V> map, DescriptionBuilder desc) {
    if (map.isEmpty()) {
      desc.expectThat("empty map", map, emptyMap());
    }

    for (Entry<K, V> entry : map.data.entrySet()) {
      desc.expectThat("non null key", entry.getKey(), is(not(nullValue())));
      desc.expectThat("non null value", entry.getValue(), is(not(nullValue())));

      if (entry.getValue() instanceof CompletableFuture<?>) {
        CompletableFuture<?> future = (CompletableFuture<?>) entry.getValue();
        boolean success = future.isDone() && !future.isCompletedExceptionally();
        desc.expectThat("future is done", success, is(true));
        desc.expectThat("not null value", future.getNow(null), is(not(nullValue())));
      }
    }
  }

  @Factory
  public static <K, V> IsValidUnboundedLocalCache<K, V> valid() {
    return new IsValidUnboundedLocalCache<K, V>();
  }
}
