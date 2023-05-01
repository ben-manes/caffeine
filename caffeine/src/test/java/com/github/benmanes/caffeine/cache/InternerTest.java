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
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.cache.LocalCacheSubject.mapLocal;
import static com.github.benmanes.caffeine.cache.testing.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.truth.Truth.assertAbout;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.References.WeakKeyEqualsReference;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.collect.testing.SetTestSuiteBuilder;
import com.google.common.collect.testing.TestStringSetGenerator;
import com.google.common.collect.testing.features.CollectionFeature;
import com.google.common.collect.testing.features.CollectionSize;
import com.google.common.testing.GcFinalization;
import com.google.common.testing.NullPointerTester;

import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class InternerTest extends TestCase {

  public static TestSuite suite() {
    return SetTestSuiteBuilder
        .using(new TestStringSetGenerator() {
          @Override protected Set<String> create(String[] elements) {
            var set = Collections.newSetFromMap(new WeakInterner<String>().cache);
            set.addAll(Arrays.asList(elements));
            return set;
          }
        })
        .named("Interner")
        .withFeatures(
            CollectionSize.ANY,
            CollectionFeature.GENERAL_PURPOSE)
        .createTestSuite();
  }

  @Test(dataProvider = "interners")
  public void intern_null(Interner<Int> interner) {
    assertThrows(NullPointerException.class, () -> interner.intern(null));
  }

  @Test(dataProvider = "interners")
  public void intern(Interner<Int> interner) {
    var canonical = new Int(1);
    var other = new Int(1);

    assertThat(interner.intern(canonical)).isSameInstanceAs(canonical);
    assertThat(interner.intern(other)).isSameInstanceAs(canonical);
    checkSize(interner, 1);

    var next = new Int(2);
    assertThat(interner.intern(next)).isSameInstanceAs(next);
    checkSize(interner, 2);
    checkState(interner);
  }

  @Test
  public void intern_weak_replace() {
    var canonical = new Int(1);
    var other = new Int(1);

    Interner<Int> interner = Interner.newWeakInterner();
    assertThat(interner.intern(canonical)).isSameInstanceAs(canonical);

    var signal = new WeakReference<>(canonical);
    canonical = null;

    GcFinalization.awaitClear(signal);
    assertThat(interner.intern(other)).isSameInstanceAs(other);
    checkSize(interner, 1);
    checkState(interner);
  }

  @Test
  public void intern_weak_remove() {
    var canonical = new Int(1);
    var next = new Int(2);

    Interner<Int> interner = Interner.newWeakInterner();
    assertThat(interner.intern(canonical)).isSameInstanceAs(canonical);

    var signal = new WeakReference<>(canonical);
    canonical = null;

    GcFinalization.awaitClear(signal);
    assertThat(interner.intern(next)).isSameInstanceAs(next);
    checkSize(interner, 1);
    checkState(interner);
  }

  @Test
  public void intern_weak_cleanup() {
    var interner = (WeakInterner<Int>) Interner.<Int>newWeakInterner();
    interner.cache.drainStatus = BoundedLocalCache.REQUIRED;

    var canonical = new Int(1);
    var interned1 = interner.intern(canonical);
    assertThat(interned1).isSameInstanceAs(canonical);
    assertThat(interner.cache.drainStatus).isEqualTo(BoundedLocalCache.IDLE);

    interner.cache.drainStatus = BoundedLocalCache.REQUIRED;
    var interned2 = interner.intern(canonical);
    assertThat(interned2).isSameInstanceAs(canonical);
    assertThat(interner.cache.drainStatus).isEqualTo(BoundedLocalCache.IDLE);
  }

  @Test
  public void nullPointerExceptions() {
    new NullPointerTester().testAllPublicStaticMethods(Interner.class);
  }

  @Test
  public void factory() {
    assertThat(Interned.FACTORY.newReferenceKey(new Object(), null))
        .isInstanceOf(WeakKeyEqualsReference.class);
    assertThat(Interned.FACTORY.newNode(null, null, null, 1, 1))
        .isInstanceOf(Interned.class);

    var builder = Caffeine.newBuilder();
    builder.interner = true;

    var factory = NodeFactory.newFactory(builder, /* async */ false);
    assertThat(factory).isSameInstanceAs(Interned.FACTORY);
  }

  @Test
  public void interned() {
    var node = new Interned<Object, Boolean>(new WeakReference<>(null));
    assertThat(node.getValue()).isTrue();
    assertThat(node.isRetired()).isFalse();
    assertThat(node.getValueReference()).isTrue();

    node.retire();
    assertThat(node.isRetired()).isTrue();

    node.die();
    assertThat(node.isDead()).isTrue();
  }

  private void checkSize(Interner<Int> interner, int size) {
    if (interner instanceof StrongInterner) {
      assertThat(((StrongInterner<Int>) interner).map).hasSize(size);
    } else if (interner instanceof WeakInterner) {
      var cache = new LocalManualCache<Int, Boolean>() {
        @Override public LocalCache<Int, Boolean> cache() {
          return ((WeakInterner<Int>) interner).cache;
        }
        @Override public Policy<Int, Boolean> policy() {
          throw new UnsupportedOperationException();
        }
      };
      assertThat(cache).whenCleanedUp().hasSize(size);
    } else {
      Assert.fail();
    }
  }

  private void checkState(Interner<Int> interner) {
    if (interner instanceof WeakInterner) {
      assertAbout(mapLocal()).that(((WeakInterner<Int>) interner).cache).isValid();
    }
  }

  @DataProvider(name = "interners")
  Object[] providesInterners() {
    return new Object[] { Interner.newStrongInterner(), Interner.newWeakInterner() };
  }
}
