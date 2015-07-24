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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import javax.cache.annotation.CacheResult;

import org.jsr107.ri.annotations.guice.module.CacheAnnotationsModule;
import org.testng.annotations.Test;

import com.google.inject.Guice;
import com.google.inject.Injector;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JCacheGuiceTest {

  @Test
  public void sanity() {
    Injector injector = Guice.createInjector(new CacheAnnotationsModule());
    Service service = injector.getInstance(Service.class);
    for (int i = 0; i < 10; i++) {
      assertThat(service.get(), is(1));
    }
    assertThat(service.times, is(1));
  }

  public static class Service {
    int times;

    @CacheResult
    public Integer get() {
      return ++times;
    }
  }
}
