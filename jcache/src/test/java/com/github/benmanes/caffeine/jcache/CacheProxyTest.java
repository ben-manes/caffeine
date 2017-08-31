package com.github.benmanes.caffeine.jcache;

import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import org.testng.annotations.Test;

import javax.cache.Cache;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.sameInstance;

public class CacheProxyTest extends AbstractJCacheTest {

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void unwrap_fail() {
    jcache.unwrap(CaffeineConfiguration.class);
  }

  @Test
  public void unwrap() {
    assertThat(jcache.unwrap(Cache.class), sameInstance(jcache));
    assertThat(jcache.unwrap(CacheProxy.class), sameInstance(jcache));
    assertThat(jcache.unwrap(com.github.benmanes.caffeine.cache.Cache.class), sameInstance(jcache.cache));
  }

  @Override
  protected CaffeineConfiguration<Integer, Integer> getConfiguration() {
    return new CaffeineConfiguration<>();
  }
}
