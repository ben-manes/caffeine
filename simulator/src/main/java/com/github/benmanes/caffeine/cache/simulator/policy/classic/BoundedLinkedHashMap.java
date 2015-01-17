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
package com.github.benmanes.caffeine.cache.simulator.policy.classic;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.concurrent.NotThreadSafe;

import com.github.benmanes.caffeine.cache.tracing.CacheEvent;
import com.github.benmanes.caffeine.cache.tracing.CacheEvent.Action;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@NotThreadSafe
final class BoundedLinkedHashMap extends LinkedHashMap<Integer, Object> {
  private static final long serialVersionUID = 1L;
  private static final Object VALUE = new Object();

  private final int maximumSize;

  private BoundedLinkedHashMap(boolean accessOrder, int maximumSize) {
    super(maximumSize, 0.75f, accessOrder);
    this.maximumSize = maximumSize;
  }

  public static BoundedLinkedHashMap asLru(int maximumSize) {
    return new BoundedLinkedHashMap(true, maximumSize);
  }

  public static BoundedLinkedHashMap asFifo(int maximumSize) {
    return new BoundedLinkedHashMap(false, maximumSize);
  }

  @Override
  protected boolean removeEldestEntry(Map.Entry<Integer, Object> eldest) {
    return size() > maximumSize;
  }

  public void handleEvent(CacheEvent event) {
    if (event.getAction() == Action.CREATE) {
      put(event.getHash(), VALUE);
    } else if (event.getAction() == Action.READ) {
      get(event.getHash());
    } else if (event.getAction() == Action.UPDATE) {
      replace(event.getHash(), VALUE);
    } else if (event.getAction() == Action.UPDATE) {
      remove(event.getHash());
    }
  }
}
