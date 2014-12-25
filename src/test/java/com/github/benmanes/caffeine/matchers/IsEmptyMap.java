/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.matchers;

import static com.github.benmanes.caffeine.matchers.IsEmptyIterable.deeplyEmpty;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.is;

import java.util.Collections;
import java.util.Map;

import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.google.common.collect.ImmutableMap;

/**
 * A matcher that performs an exhaustive empty check throughout the {@link Map} contract.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsEmptyMap<K, V>
    extends TypeSafeDiagnosingMatcher<Map<? extends K, ? extends V>> {

  @Override
  public void describeTo(Description description) {
    description.appendText("emptyMap");
  }

  @Override
  protected boolean matchesSafely(Map<? extends K, ? extends V> map, Description description) {
    DescriptionBuilder builder = new DescriptionBuilder(description);

    builder.expectThat(map.keySet(), is(deeplyEmpty()));
    builder.expectThat(map.values(), is(deeplyEmpty()));
    builder.expectThat(map.entrySet(), is(deeplyEmpty()));
    builder.expectThat(map, is(Collections.EMPTY_MAP));
    builder.expectThat("Size != 0", map.size(), is(0));
    builder.expectThat("Not empty", map.isEmpty(), is(true));
    builder.expectThat("hashcode", map.hashCode(), is(ImmutableMap.of().hashCode()));
    builder.expectThat("toString", map, hasToString(ImmutableMap.of().toString()));
    return builder.matches();
  }

  @Factory
  public static <K, V> IsEmptyMap<K, V> emptyMap() {
    return new IsEmptyMap<K, V>();
  }
}
