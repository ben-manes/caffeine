/*
 * Written by Doug Lea and Martin Buchholz with assistance from
 * members of JCP JSR-166 Expert Group and released to the public
 * domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */
package com.github.benmanes.caffeine.jsr166;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import junit.framework.Test;

@SuppressWarnings("rawtypes")
public class KeySetTest extends JSR166TestCase {
    public static void main(String[] args) {
        main(suite(), args);
    }

    public static Test suite() {
        class Implementation implements CollectionImplementation {
            final boolean bounded;

            Implementation(boolean bounded) {
              this.bounded = bounded;
            }

            @Override
            public Class<?> klazz() { return Set.class; }
            @Override
            public Collection emptyCollection() { return set(bounded); }
            @Override
            public Object makeElement(int i) { return JSR166TestCase.itemFor(i); }
            @Override
            public boolean isConcurrent() { return true; }
            @Override
            public boolean permitsNulls() { return false; }
        }
        return newTestSuite(
                KeySetTest.class,
                CollectionTest.testSuite(new Implementation(false)),
                CollectionTest.testSuite(new Implementation(true)));
    }

    private static <E> Set<E> set(boolean bounded) {
        var builder = Caffeine.newBuilder();
        if (bounded) {
          builder.maximumSize(Integer.MAX_VALUE);
        }
        Cache<E, Boolean> cache = builder.build();
        return Collections.newSetFromMap(cache.asMap());
    }

    public void testIgnore() {}
}
