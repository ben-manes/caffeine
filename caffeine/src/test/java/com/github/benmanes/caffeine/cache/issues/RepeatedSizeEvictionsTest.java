/*
 * Copyright 2016 Metamarkets Group, Inc. All Rights Reserved.
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

package com.github.benmanes.caffeine.cache.issues;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import org.testng.Assert;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

@Listeners(CacheValidationListener.class)
public class RepeatedSizeEvictionsTest
{
  @Test
  public void testSizeEviciton() throws Exception
  {
    final String key1 = "key1";
    final String key2 = "key2";
    final String val1 = "val1";
    final String val2 = "val2";

    final Cache<String, String> caffeine = Caffeine
        .newBuilder()
        .executor(Runnable::run)
        .maximumWeight(10)
        .weigher((String key, String value) -> key.length() + value.length())
        .build();
    Assert.assertNull(caffeine.getIfPresent(key1));
    Assert.assertNull(caffeine.getIfPresent(key2));

    caffeine.put(key1, val1);

    Assert.assertEquals(val1, caffeine.getIfPresent(key1));

    caffeine.put(key2, val2);

    Assert.assertNull(caffeine.getIfPresent(key1));

    Assert.assertEquals(val2, caffeine.getIfPresent(key2));
  }

  @Test
  public void testRepeatedEvictions() throws Exception
  {
    for(int i = 0; i < 1000; ++i) {
      try {
        testSizeEviciton();
      }
      catch (AssertionError e) {
        throw new AssertionError(String.format("Failed after %d", i), e);
      }
    }
  }
}
