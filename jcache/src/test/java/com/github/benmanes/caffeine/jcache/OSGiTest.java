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
package com.github.benmanes.caffeine.jcache;

import static org.junit.Assert.assertNull;
import static org.ops4j.pax.exam.CoreOptions.bundle;
import static org.ops4j.pax.exam.CoreOptions.junitBundles;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.options;

import javax.cache.Cache;
import javax.cache.Caching;
import javax.cache.spi.CachingProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerMethod;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerMethod.class)
public final class OSGiTest {

  @Configuration
  public Option[] config() {
    return options(
        junitBundles(),
        bundle("file:" + System.getProperty("caffeine.osgi.jar")),
        bundle("file:" + System.getProperty("caffeine-jcache.osgi.jar")),
        mavenBundle("com.typesafe", "config", System.getProperty("config.osgi.version")),
        mavenBundle("javax.cache", "cache-api", System.getProperty("jcache.osgi.version")));
  }

  @Test
  public void sanity() {
    CachingProvider cachingProvider = Caching.getCachingProvider(
        "com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider",
        getClass().getClassLoader());
    Cache<String, Integer> cache = cachingProvider.getCacheManager()
        .getCache("test-cache-2", String.class, Integer.class);
    assertNull(cache.get("a"));
  }
}
