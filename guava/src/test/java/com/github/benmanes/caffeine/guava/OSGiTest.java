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
package com.github.benmanes.caffeine.guava;

import static org.junit.Assert.assertEquals;
import static org.ops4j.pax.exam.CoreOptions.bundle;
import static org.ops4j.pax.exam.CoreOptions.junitBundles;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.options;

import org.junit.Test;
import org.junit.experimental.categories.Categories.IncludeCategory;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerMethod;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@RunWith(PaxExam.class)
@IncludeCategory(OSGiTests.class)
@ExamReactorStrategy(PerMethod.class)
public final class OSGiTest {

  @Configuration
  public Option[] config() {
    return options(
        junitBundles(),
        bundle("file:" + System.getProperty("caffeine.osgi.jar")),
        bundle("file:" + System.getProperty("caffeine-guava.osgi.jar")),
        mavenBundle("com.google.guava", "failureaccess", "1.0.1"),
        mavenBundle("com.google.guava", "guava", System.getProperty("guava.osgi.version")));
  }

  @Test
  @Category(OSGiTests.class)
  public void sanity() {
    CacheLoader<Integer, Integer> loader = new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) {
        return -key;
      }
    };
    LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(Caffeine.newBuilder(), loader);
    assertEquals(-1, cache.getUnchecked(1).intValue());
  }
}
