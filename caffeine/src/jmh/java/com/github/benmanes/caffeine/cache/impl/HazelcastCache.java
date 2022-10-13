/*
 * Copyright 2022 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.impl;

import static com.hazelcast.config.MaxSizePolicy.ENTRY_COUNT;

import com.github.benmanes.caffeine.cache.BasicCache;
import com.hazelcast.config.EvictionConfig;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.NearCacheConfig;
import com.hazelcast.core.ManagedContext;
import com.hazelcast.internal.nearcache.NearCache;
import com.hazelcast.internal.nearcache.impl.DefaultNearCache;
import com.hazelcast.internal.serialization.Data;
import com.hazelcast.internal.serialization.SerializationService;
import com.hazelcast.partition.PartitioningStrategy;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class HazelcastCache<K, V> implements BasicCache<K, V> {
  private final NearCache<K, V> cache;

  public HazelcastCache(int maximumSize, EvictionPolicy policy) {
    var config = new NearCacheConfig()
        .setSerializeKeys(false)
        .setInMemoryFormat(InMemoryFormat.OBJECT)
        .setEvictionConfig(new EvictionConfig()
            .setMaxSizePolicy(ENTRY_COUNT)
            .setEvictionPolicy(policy)
            .setSize(maximumSize));
    cache = new DefaultNearCache<>("simulation", config, DummySerializationService.INSTANCE,
        /* TaskScheduler */ null, getClass().getClassLoader(), /* HazelcastProperties */ null);
    cache.initialize();
  }

  @Override
  public V get(K key) {
    return cache.get(key);
  }

  @Override
  public void put(K key, V value) {
    cache.put(key, /* keyData */ null, value, /* valueDate */ null);
  }

  @Override
  public void remove(K key) {
    cache.invalidate(key);
  }

  @Override
  public void clear() {
    cache.clear();
  }

  @SuppressWarnings({"rawtypes", "TypeParameterUnusedInFormals", "unchecked"})
  enum DummySerializationService implements SerializationService {
    INSTANCE;

    @Override public <B extends Data> B toData(Object obj) {
      return (B) obj;
    }
    @Override public <B extends Data> B toDataWithSchema(Object obj) {
      return (B) obj;
    }
    @Override public <B extends Data> B toData(Object obj, PartitioningStrategy strategy) {
      return (B) obj;
    }
    @Override public <T> T toObject(Object data) {
      return (T) data;
    }
    @Override public <T> T toObject(Object data, Class klazz) {
      return (T) data;
    }
    @Override public ManagedContext getManagedContext() {
      return null;
    }
    @Override public <B extends Data> B trimSchema(Data data) {
      return (B) data;
    }
  }
}
