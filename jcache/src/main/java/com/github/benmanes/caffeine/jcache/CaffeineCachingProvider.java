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

import java.net.URI;
import java.util.Properties;

import javax.cache.CacheManager;
import javax.cache.configuration.OptionalFeature;
import javax.cache.spi.CachingProvider;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeineCachingProvider implements CachingProvider {

  @Override
  public CacheManager getCacheManager(URI uri, ClassLoader classLoader, Properties properties) {
    return null;
  }

  @Override
  public ClassLoader getDefaultClassLoader() {
    return null;
  }

  @Override
  public URI getDefaultURI() {
    return null;
  }

  @Override
  public Properties getDefaultProperties() {
    return null;
  }

  @Override
  public CacheManager getCacheManager(URI uri, ClassLoader classLoader) {
    return null;
  }

  @Override
  public CacheManager getCacheManager() {
    return null;
  }

  @Override
  public void close() {}

  @Override
  public void close(ClassLoader classLoader) {}

  @Override
  public void close(URI uri, ClassLoader classLoader) {}

  @Override
  public boolean isSupported(OptionalFeature optionalFeature) {
    return false;
  }
}
