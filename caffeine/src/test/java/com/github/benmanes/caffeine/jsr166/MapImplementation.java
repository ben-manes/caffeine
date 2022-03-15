/*
 * Written by Doug Lea and Martin Buchholz with assistance from
 * members of JCP JSR-166 Expert Group and released to the public
 * domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */
package com.github.benmanes.caffeine.jsr166;

import java.util.Map;

/** Allows tests to work with different Map implementations. */
@SuppressWarnings({"rawtypes", "unchecked"})
public interface MapImplementation {
    /** Returns the Map implementation class. */
    public Class<?> klazz();
    /** Returns an empty map. */
    public Map emptyMap();

    // General purpose implementations can use Integers for key and value
    default Object makeKey(int i) { return i; }
    default Object makeValue(int i) { return i; }
    default int keyToInt(Object key) { return (Integer) key; }
    default int valueToInt(Object value) { return (Integer) value; }

    public boolean isConcurrent();
    default boolean remappingFunctionCalledAtMostOnce() { return true; };
    public boolean permitsNullKeys();
    public boolean permitsNullValues();
    public boolean supportsSetValue();
}
