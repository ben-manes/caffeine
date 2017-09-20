/*
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
import static org.hamcrest.Matchers.sameInstance;

import javax.cache.Cache;

import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;

/**
 * @author github.com/kdombeck (Ken Dombeck)
 */
public final class CacheProxyTest extends AbstractJCacheTest {

  @Override
  protected CaffeineConfiguration<Integer, Integer> getConfiguration() {
    return new CaffeineConfiguration<>();
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void unwrap_fail() {
    jcache.unwrap(CaffeineConfiguration.class);
  }

  @Test
  public void unwrap() {
    assertThat(jcache.unwrap(Cache.class), sameInstance(jcache));
    assertThat(jcache.unwrap(CacheProxy.class), sameInstance(jcache));
    assertThat(jcache.unwrap(com.github.benmanes.caffeine.cache.Cache.class),
        sameInstance(jcache.cache));
  }
}
