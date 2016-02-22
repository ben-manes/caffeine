/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */
package jsr166;

import static java.util.Spliterator.CONCURRENT;
import static java.util.Spliterator.DISTINCT;
import static java.util.Spliterator.NONNULL;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

import com.github.benmanes.caffeine.cache.Caffeine;

import junit.framework.Test;
import junit.framework.TestSuite;

@SuppressWarnings({"rawtypes", "unchecked"})
public class ConcurrentHashMap8Test extends JSR166TestCase {
    public static void main(String[] args) {
        junit.textui.TestRunner.run(suite());
    }
    public static Test suite() {
        return new TestSuite(ConcurrentHashMap8Test.class);
    }

    private static <K, V> ConcurrentMap<K, V> map() {
      return Caffeine.newBuilder()
          .maximumSize(Integer.MAX_VALUE)
          .<K, V>build().asMap();
    }

    private static <E> Set<E> set() {
      return Collections.newSetFromMap(map());
    }

    /**
     * Returns a new map from Integers 1-5 to Strings "A"-"E".
     */
    private static ConcurrentMap map5() {
        ConcurrentMap map = map();
        assertTrue(map.isEmpty());
        map.put(one, "A");
        map.put(two, "B");
        map.put(three, "C");
        map.put(four, "D");
        map.put(five, "E");
        assertFalse(map.isEmpty());
        assertEquals(5, map.size());
        return map;
    }

    /**
     * getOrDefault returns value if present, else default
     */
    public void testGetOrDefault() {
        ConcurrentMap map = map5();
        assertEquals(map.getOrDefault(one, "Z"), "A");
        assertEquals(map.getOrDefault(six, "Z"), "Z");
    }

    /**
     * computeIfAbsent adds when the given key is not present
     */
    public void testComputeIfAbsent() {
        ConcurrentMap map = map5();
        map.computeIfAbsent(six, (x) -> "Z");
        assertTrue(map.containsKey(six));
    }

    /**
     * computeIfAbsent does not replace if the key is already present
     */
    public void testComputeIfAbsent2() {
        ConcurrentMap map = map5();
        assertEquals("A", map.computeIfAbsent(one, (x) -> "Z"));
    }

    /**
     * computeIfAbsent does not add if function returns null
     */
    public void testComputeIfAbsent3() {
        ConcurrentMap map = map5();
        map.computeIfAbsent(six, (x) -> null);
        assertFalse(map.containsKey(six));
    }

    /**
     * computeIfPresent does not replace if the key is already present
     */
    public void testComputeIfPresent() {
        ConcurrentMap map = map5();
        map.computeIfPresent(six, (x, y) -> "Z");
        assertFalse(map.containsKey(six));
    }

    /**
     * computeIfPresent adds when the given key is not present
     */
    public void testComputeIfPresent2() {
        ConcurrentMap map = map5();
        assertEquals("Z", map.computeIfPresent(one, (x, y) -> "Z"));
    }

    /**
     * compute does not replace if the function returns null
     */
    public void testCompute() {
        ConcurrentMap map = map5();
        map.compute(six, (x, y) -> null);
        assertFalse(map.containsKey(six));
    }

    /**
     * compute adds when the given key is not present
     */
    public void testCompute2() {
        ConcurrentMap map = map5();
        assertEquals("Z", map.compute(six, (x, y) -> "Z"));
    }

    /**
     * compute replaces when the given key is present
     */
    public void testCompute3() {
        ConcurrentMap map = map5();
        assertEquals("Z", map.compute(one, (x, y) -> "Z"));
    }

    /**
     * compute removes when the given key is present and function returns null
     */
    public void testCompute4() {
        ConcurrentMap map = map5();
        map.compute(one, (x, y) -> null);
        assertFalse(map.containsKey(one));
    }

    /**
     * merge adds when the given key is not present
     */
    public void testMerge1() {
        ConcurrentMap map = map5();
        assertEquals("Y", map.merge(six, "Y", (x, y) -> "Z"));
    }

    /**
     * merge replaces when the given key is present
     */
    public void testMerge2() {
        ConcurrentMap map = map5();
        assertEquals("Z", map.merge(one, "Y", (x, y) -> "Z"));
    }

    /**
     * merge removes when the given key is present and function returns null
     */
    public void testMerge3() {
        ConcurrentMap map = map5();
        map.merge(one, "Y", (x, y) -> null);
        assertFalse(map.containsKey(one));
    }

    static Set<Integer> populatedSet(int n) {
        Set<Integer> a = set();
        assertTrue(a.isEmpty());
        for (int i = 0; i < n; i++) {
          assertTrue(a.add(i));
        }
        assertEquals(n == 0, a.isEmpty());
        assertEquals(n, a.size());
        return a;
    }

    static Set populatedSet(Integer[] elements) {
        Set<Integer> a = set();
        assertTrue(a.isEmpty());
        for (int i = 0; i < elements.length; i++) {
          assertTrue(a.add(elements[i]));
        }
        assertFalse(a.isEmpty());
        assertEquals(elements.length, a.size());
        return a;
    }

    /**
     * replaceAll replaces all matching values.
     */
    public void testReplaceAll() {
        ConcurrentMap<Integer, String> map = map5();
        map.replaceAll((x, y) -> { return x > 3 ? "Z" : y; });
        assertEquals("A", map.get(one));
        assertEquals("B", map.get(two));
        assertEquals("C", map.get(three));
        assertEquals("Z", map.get(four));
        assertEquals("Z", map.get(five));
    }

    /**
     * Default-constructed set is empty
     */
    public void testNewKeySet() {
        Set a = set();
        assertTrue(a.isEmpty());
    }

    /**
     * keySet.add adds the key with the established value to the map;
     * remove removes it.
     */
    public void testKeySetAddRemove() {
        ConcurrentMap map = map5();
        Set set1 = map.keySet();
        map.put(six, true);
        assertEquals(set1.size(), map.size());
        assertTrue((Boolean)map.get(six));
        assertTrue(set1.contains(six));
        map.remove(six);
        assertNull(map.get(six));
        assertFalse(set1.contains(six));
    }

    /**
     * keySet.addAll adds each element from the given collection
     */
    public void testAddAll() {
        Set full = populatedSet(3);
        assertTrue(full.addAll(Arrays.asList(three, four, five)));
        assertEquals(6, full.size());
        assertFalse(full.addAll(Arrays.asList(three, four, five)));
        assertEquals(6, full.size());
    }

    /**
     * keySet.addAll adds each element from the given collection that did not
     * already exist in the set
     */
    public void testAddAll2() {
        Set full = populatedSet(3);
        // "one" is duplicate and will not be added
        assertTrue(full.addAll(Arrays.asList(three, four, one)));
        assertEquals(5, full.size());
        assertFalse(full.addAll(Arrays.asList(three, four, one)));
        assertEquals(5, full.size());
    }

    /**
     * keySet.add will not add the element if it already exists in the set
     */
    public void testAdd2() {
        Set full = populatedSet(3);
        assertFalse(full.add(one));
        assertEquals(3, full.size());
    }

    /**
     * keySet.add adds the element when it does not exist in the set
     */
    public void testAdd3() {
        Set full = populatedSet(3);
        assertTrue(full.add(three));
        assertTrue(full.contains(three));
        assertFalse(full.add(three));
        assertTrue(full.contains(three));
    }

    /**
     * keySet.add throws UnsupportedOperationException if no default
     * mapped value
     */
    public void testAdd4() {
        Set full = map5().keySet();
        try {
            full.add(three);
            shouldThrow();
        } catch (UnsupportedOperationException success) {}
    }

    /**
     * keySet.add throws NullPointerException if the specified key is
     * null
     */
    public void testAdd5() {
        Set full = populatedSet(3);
        try {
            full.add(null);
            shouldThrow();
        } catch (NullPointerException success) {}
    }

    void checkSpliteratorCharacteristics(Spliterator<?> sp,
                                         int requiredCharacteristics) {
        assertEquals(requiredCharacteristics,
                     requiredCharacteristics & sp.characteristics());
    }

    /**
     * KeySetView.spliterator returns spliterator over the elements in this set
     */
    public void testKeySetSpliterator() {
        LongAdder adder = new LongAdder();
        ConcurrentMap map = map5();
        Set set = map.keySet();
        Spliterator<Integer> sp = set.spliterator();
        checkSpliteratorCharacteristics(sp, CONCURRENT | DISTINCT | NONNULL);
        assertEquals(sp.estimateSize(), map.size());
        Spliterator<Integer> sp2 = sp.trySplit();
        sp.forEachRemaining((Integer x) -> adder.add(x.longValue()));
        long v = adder.sumThenReset();
        sp2.forEachRemaining((Integer x) -> adder.add(x.longValue()));
        long v2 = adder.sum();
        assertEquals(v + v2, 15);
    }

    /**
     * keyset.clear removes all elements from the set
     */
    public void testClear() {
        Set full = populatedSet(3);
        full.clear();
        assertEquals(0, full.size());
    }

    /**
     * keyset.contains returns true for added elements
     */
    public void testContains() {
        Set full = populatedSet(3);
        assertTrue(full.contains(one));
        assertFalse(full.contains(five));
    }

    /**
     * KeySets with equal elements are equal
     */
    public void testEquals() {
        Set a = populatedSet(3);
        Set b = populatedSet(3);
        assertTrue(a.equals(b));
        assertTrue(b.equals(a));
        assertEquals(a.hashCode(), b.hashCode());
        a.add(m1);
        assertFalse(a.equals(b));
        assertFalse(b.equals(a));
        b.add(m1);
        assertTrue(a.equals(b));
        assertTrue(b.equals(a));
        assertEquals(a.hashCode(), b.hashCode());
    }

    /**
     * KeySet.containsAll returns true for collections with subset of elements
     */
    public void testContainsAll() {
        Collection full = populatedSet(3);
        assertTrue(full.containsAll(Arrays.asList()));
        assertTrue(full.containsAll(Arrays.asList(one)));
        assertTrue(full.containsAll(Arrays.asList(one, two)));
        assertFalse(full.containsAll(Arrays.asList(one, two, six)));
        assertFalse(full.containsAll(Arrays.asList(six)));
    }

    /**
     * KeySet.isEmpty is true when empty, else false
     */
    public void testIsEmpty() {
        assertTrue(populatedSet(0).isEmpty());
        assertFalse(populatedSet(3).isEmpty());
    }

    /**
     * KeySet.iterator() returns an iterator containing the elements of the
     * set
     */
    public void testIterator() {
        Collection empty = set();
        int size = 20;
        assertFalse(empty.iterator().hasNext());
        try {
            empty.iterator().next();
            shouldThrow();
        } catch (NoSuchElementException success) {}

        Integer[] elements = new Integer[size];
        for (int i = 0; i < size; i++) {
          elements[i] = i;
        }
        Collections.shuffle(Arrays.asList(elements));
        Collection<Integer> full = populatedSet(elements);

        Iterator it = full.iterator();
        for (int j = 0; j < size; j++) {
            assertTrue(it.hasNext());
            it.next();
        }
        assertIteratorExhausted(it);
    }

    /**
     * iterator of empty collections has no elements
     */
    public void testEmptyIterator() {
        assertIteratorExhausted(set().iterator());
        assertIteratorExhausted(map().entrySet().iterator());
        assertIteratorExhausted(map().values().iterator());
        assertIteratorExhausted(map().keySet().iterator());
    }

    /**
     * KeySet.iterator.remove removes current element
     */
    public void testIteratorRemove() {
        Set q = populatedSet(3);
        Iterator it = q.iterator();
        Object removed = it.next();
        it.remove();

        it = q.iterator();
        assertFalse(it.next().equals(removed));
        assertFalse(it.next().equals(removed));
        assertFalse(it.hasNext());
    }

    /**
     * KeySet.toString holds toString of elements
     */
    public void testToString() {
        assertEquals("[]", set().toString());
        Set full = populatedSet(3);
        String s = full.toString();
        for (int i = 0; i < 3; ++i) {
          assertTrue(s.contains(String.valueOf(i)));
        }
    }

    /**
     * KeySet.removeAll removes all elements from the given collection
     */
    public void testRemoveAll() {
        Set full = populatedSet(3);
        assertTrue(full.removeAll(Arrays.asList(one, two)));
        assertEquals(1, full.size());
        assertFalse(full.removeAll(Arrays.asList(one, two)));
        assertEquals(1, full.size());
    }

    /**
     * KeySet.remove removes an element
     */
    public void testRemove() {
        Set full = populatedSet(3);
        full.remove(one);
        assertFalse(full.contains(one));
        assertEquals(2, full.size());
    }

    /**
     * keySet.size returns the number of elements
     */
    public void testSize() {
        Set empty = set();
        Set full = populatedSet(3);
        assertEquals(3, full.size());
        assertEquals(0, empty.size());
    }

    /**
     * KeySet.toArray() returns an Object array containing all elements from
     * the set
     */
    public void testToArray() {
        Object[] a = set().toArray();
        assertTrue(Arrays.equals(new Object[0], a));
        assertSame(Object[].class, a.getClass());
        int size = 20;
        Integer[] elements = new Integer[size];
        for (int i = 0; i < size; i++) {
          elements[i] = i;
        }
        Collections.shuffle(Arrays.asList(elements));
        Collection<Integer> full = populatedSet(elements);

        assertTrue(Arrays.asList(elements).containsAll(Arrays.asList(full.toArray())));
        assertTrue(full.containsAll(Arrays.asList(full.toArray())));
        assertSame(Object[].class, full.toArray().getClass());
    }

    /**
     * toArray(Integer array) returns an Integer array containing all
     * elements from the set
     */
    public void testToArray2() {
        Collection empty = set();
        Integer[] a;
        int size = 20;

        a = new Integer[0];
        assertSame(a, empty.toArray(a));

        a = new Integer[size/2];
        Arrays.fill(a, 42);
        assertSame(a, empty.toArray(a));
        assertNull(a[0]);
        for (int i = 1; i < a.length; i++) {
          assertEquals(42, (int) a[i]);
        }

        Integer[] elements = new Integer[size];
        for (int i = 0; i < size; i++) {
          elements[i] = i;
        }
        Collections.shuffle(Arrays.asList(elements));
        Collection<Integer> full = populatedSet(elements);

        Arrays.fill(a, 42);
        assertTrue(Arrays.asList(elements).containsAll(Arrays.asList(full.toArray(a))));
        for (int i = 0; i < a.length; i++) {
          assertEquals(42, (int) a[i]);
        }
        assertSame(Integer[].class, full.toArray(a).getClass());

        a = new Integer[size];
        Arrays.fill(a, 42);
        assertSame(a, full.toArray(a));
        assertTrue(Arrays.asList(elements).containsAll(Arrays.asList(full.toArray(a))));
    }
}
