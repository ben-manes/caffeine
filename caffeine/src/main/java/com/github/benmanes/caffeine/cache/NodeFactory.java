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
package com.github.benmanes.caffeine.cache;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.ReferenceQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.github.benmanes.caffeine.cache.References.LookupKeyReference;
import com.github.benmanes.caffeine.cache.References.WeakKeyReference;
import com.google.errorprone.annotations.Var;

/**
 * A factory for cache nodes optimized for a particular configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
interface NodeFactory<K, V> {
  MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  MethodType FACTORY = MethodType.methodType(void.class);
  ConcurrentMap<String, NodeFactory<Object, Object>> FACTORIES = new ConcurrentHashMap<>();

  RetiredStrongKey RETIRED_STRONG_KEY = new RetiredStrongKey();
  RetiredWeakKey RETIRED_WEAK_KEY = new RetiredWeakKey();
  DeadStrongKey DEAD_STRONG_KEY = new DeadStrongKey();
  DeadWeakKey DEAD_WEAK_KEY = new DeadWeakKey();
  String ACCESS_TIME = "accessTime";
  String WRITE_TIME = "writeTime";
  String VALUE = "value";
  String KEY = "key";

  /** Returns whether this factory supports weak values. */
  default boolean weakValues() {
    return false;
  }

  /** Returns whether this factory supports soft values. */
  default boolean softValues() {
    return false;
  }

  /** Returns a node optimized for the specified features. */
  Node<K, V> newNode(K key, ReferenceQueue<K> keyReferenceQueue, V value,
      ReferenceQueue<V> valueReferenceQueue, int weight, long now);

  /** Returns a node optimized for the specified features. */
  Node<K, V> newNode(Object keyReference, V value,
      ReferenceQueue<V> valueReferenceQueue, int weight, long now);

  /**
   * Returns a key suitable for inserting into the cache. If the cache holds keys strongly then the
   * key is returned. If the cache holds keys weakly then a {@link java.lang.ref.Reference<K>}
   * holding the key argument is returned.
   */
  default Object newReferenceKey(K key, ReferenceQueue<K> referenceQueue) {
    return key;
  }

  /**
   * Returns a key suitable for looking up an entry in the cache. If the cache holds keys strongly
   * then the key is returned. If the cache holds keys weakly then a {@link LookupKeyReference}
   * holding the key argument is returned.
   */
  default Object newLookupKey(Object key) {
    return key;
  }

  /** Returns a factory optimized for the specified features. */
  @SuppressWarnings("unchecked")
  static <K, V> NodeFactory<K, V> newFactory(Caffeine<K, V> builder, boolean isAsync) {
    if (builder.interner) {
      return (NodeFactory<K, V>) Interned.FACTORY;
    }
    var className = getClassName(builder, isAsync);
    return loadFactory(className);
  }

  static String getClassName(Caffeine<?, ?> builder, boolean isAsync) {
    var className = new StringBuilder();
    if (builder.isStrongKeys()) {
      className.append('P');
    } else {
      className.append('F');
    }
    if (builder.isStrongValues()) {
      className.append('S');
    } else if (builder.isWeakValues()) {
      className.append('W');
    } else {
      className.append('D');
    }
    if (builder.expiresVariable()) {
      if (builder.refreshAfterWrite()) {
        className.append('A');
        if (builder.evicts()) {
          className.append('W');
        }
      } else {
        className.append('W');
      }
    } else {
      if (builder.expiresAfterAccess()) {
        className.append('A');
      }
      if (builder.expiresAfterWrite()) {
        className.append('W');
      }
    }
    if (builder.refreshAfterWrite()) {
      className.append('R');
    }
    if (builder.evicts()) {
      className.append('M');
      if (isAsync || (builder.isWeighted() && (builder.weigher != Weigher.singletonWeigher()))) {
        className.append('W');
      } else {
        className.append('S');
      }
    }
    return className.toString();
  }

  @SuppressWarnings("unchecked")
  static <K, V> NodeFactory<K, V> loadFactory(String className) {
    @Var var factory = FACTORIES.get(className);
    if (factory == null) {
      factory = FACTORIES.computeIfAbsent(className, NodeFactory::newFactory);
    }
    return (NodeFactory<K, V>) factory;
  }

  @SuppressWarnings("unchecked")
  static NodeFactory<Object, Object> newFactory(String className) {
    try {
      var clazz = LOOKUP.findClass(Node.class.getPackageName() + "." + className);
      var constructor = LOOKUP.findConstructor(clazz, FACTORY);
      return (NodeFactory<Object, Object>) constructor.invoke();
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(className, t);
    }
  }

  final class RetiredWeakKey extends WeakKeyReference<Object> {
    RetiredWeakKey() { super(/* key= */ null, /* queue= */ null); }
  }
  final class DeadWeakKey extends WeakKeyReference<Object> {
    DeadWeakKey() { super(/* key= */ null, /* queue= */ null); }
  }
  final class RetiredStrongKey {}
  final class DeadStrongKey {}
}
