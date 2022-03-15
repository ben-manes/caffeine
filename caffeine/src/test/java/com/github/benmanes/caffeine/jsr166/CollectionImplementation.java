/*
 * Written by Doug Lea and Martin Buchholz with assistance from
 * members of JCP JSR-166 Expert Group and released to the public
 * domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */
package com.github.benmanes.caffeine.jsr166;

import java.util.Collection;

/** Allows tests to work with different Collection implementations. */
@SuppressWarnings("rawtypes")
public interface CollectionImplementation {
    /** Returns the Collection class. */
    public Class<?> klazz();
    /** Returns an empty collection. */
    public Collection emptyCollection();
    public Object makeElement(int i);
    public boolean isConcurrent();
    public boolean permitsNulls();
}
