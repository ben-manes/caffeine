/*
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
@SuppressWarnings({"IdentifierName", "MethodCanBeStatic", "PMD.LooseCoupling",
    "PMD.UseUnderscoresInNumericLiterals", "PreferredInterfaceType"})
public class NBHMRemoveTest {

  public static final Long TEST_KEY_OTHER = 123777L;
  public static final Long TEST_KEY_0 = 0L;

  @Parameterized.Parameters
  public static Collection<Object[]> parameters() {
    ArrayList<Object[]> list = new ArrayList<>();
    // Verify the test assumptions against JDK reference implementations, useful for debugging
    // list.add(new Object[]{new HashMap<>(), TEST_KEY_0, "0", "1"});
    // list.add(new Object[]{new ConcurrentHashMap<>(), TEST_KEY_0, "0", "1"});
    // list.add(new Object[]{new Hashtable<>(), TEST_KEY_0, "0", "1"});

    // Test with special key
    list.add(new Object[] {Caffeine.newBuilder().build().asMap(), TEST_KEY_0, "0", "1"});
    list.add(new Object[] {Caffeine.newBuilder().maximumSize(1000).build().asMap(), TEST_KEY_0, "0",
        "1"});

    // Test with some other key
    list.add(new Object[] {Caffeine.newBuilder().build().asMap(), TEST_KEY_OTHER, "0", "1"});
    list.add(new Object[] {Caffeine.newBuilder().maximumSize(1000).build().asMap(), TEST_KEY_OTHER,
        "0", "1"});

    return list;
  }

  final Map<Long, String> map;
  final Long key;
  final String v1;
  final String v2;

  public NBHMRemoveTest(Map<Long, String> map, Long key, String v1, String v2) {
    this.map = map;
    this.key = key;
    this.v1 = v1;
    this.v2 = v2;
  }

  @After
  public void clear() {
    map.clear();
  }

  /*
   * This test demonstrates a retention issue in the NBHM implementation which keeps old keys around
   * until a resize event. See https://github.com/JCTools/JCTools/issues/354
   */
//  @Test
//  public void removeRetainsKey() {
//    assumeThat(map, is(instanceOf(NonBlockingHashMap.class)));
//    assumeThat(key, is(TEST_KEY_0));
//
//    Long key1 = Long.valueOf(42424242);
//    Long key2 = Long.valueOf(42424242);
//    assertEquals(key1, key2);
//    assertFalse(key1 == key2);
//    // key1 and key2 are different instances with same hash/equals
//    map.put(key1, "a");
//    map.remove(key1);
//    if (map instanceof NonBlockingHashMap) {
//      assertTrue(contains(((NonBlockingHashMap) map).raw_array(), key1));
//    }
//    map.put(key2, "a");
//    if (map instanceof NonBlockingHashMap) {
//      assertFalse(contains(((NonBlockingHashMap) map).raw_array(), key2));
//    }
//    // key1 remains in the map
//    Set<Long> keySet = map.keySet();
//    assertEquals(keySet.size(), 1);
//    if (map instanceof NonBlockingHashMap) {
//      assertTrue(keySet.toArray()[0] == key1);
//    }
//  }

  /*
   * This test demonstrates a retention issue in the NBHM implementation which keeps old keys around
   * until a resize event. See https://github.com/JCTools/JCTools/issues/354
   */
//  @Test
//  public void removeRetainsKey2() {
//    assumeThat(map, is(instanceOf(NonBlockingHashMap.class)));
//    assumeThat(key, is(TEST_KEY_0));
//
//    String key1 = "Aa";
//    String key2 = "BB";
//    NonBlockingHashMap<String, String> map = new NonBlockingHashMap<>();
//    assertEquals(key1.hashCode(), key2.hashCode());
//    assertNotEquals(key1, key2);
//    assertFalse(key1 == key2);
//    // key1 and key2 are different instances with same hash, but different equals
//    map.put(key1, "a");
//    map.remove(key1);
//    map.put(key2, "a");
//    // key1 remains in the map
//    Set<String> keySet = map.keySet();
//    assertEquals(keySet.size(), 1);
//    assertEquals(keySet.toArray()[0], key2);
//    Object[] raw_array = map.raw_array();
//    assertTrue(contains(raw_array, key1));
//    assertTrue(contains(raw_array, key2));
//  }
//
//  private boolean contains(Object[] raw_array, Object v) {
//    for (int i = 0; i < raw_array.length; i++) {
//      if (raw_array[i] == v) {
//        return true;
//      }
//    }
//    return false;
//  }

  @Test
  public void directRemoveKey() {
    installValue(map, key, v1);
    assertEquals(v1, map.remove(key));
    postRemoveAsserts(map, key);
    assertFalse(map.containsValue(v1));
  }

  @Test
  public void keySetIteratorRemoveKey() {
    installValue(map, key, v1);
    Iterator<Long> iterator = map.keySet().iterator();
    while (iterator.hasNext()) {
      if (key.equals(iterator.next())) {
        iterator.remove();
        break;
      }
    }
    postRemoveAsserts(map, key);
    assertFalse(map.containsValue(v1));
  }

  @Test
  public void keySetIteratorRemoveKeyAfterValChange() {
    installValue(map, key, v1);
    Iterator<Long> iterator = map.keySet().iterator();
    map.put(key, v2);
    while (iterator.hasNext()) {
      if (key.equals(iterator.next())) {
        iterator.remove();
        break;
      }
    }
    postRemoveAsserts(map, key);
    assertFalse(map.containsValue(v1));
    assertFalse(map.containsValue(v2));
  }

  @Test
  public void entriesIteratorRemoveKey() {
    installValue(map, key, v1);
    Iterator<Map.Entry<Long, String>> iterator = map.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<Long, String> entry = iterator.next();
      if (key.equals(entry.getKey())) {
        iterator.remove();
        break;
      }
    }
    postRemoveAsserts(map, key);
    assertFalse(map.containsValue(v1));
  }

  @Test
  public void entriesIteratorRemoveKeyAfterValChange() {
    installValue(map, key, v1);
    Iterator<Map.Entry<Long, String>> iterator = map.entrySet().iterator();
    assertTrue(iterator.hasNext());
    Map.Entry<Long, String> entry = iterator.next();
    assertEquals(key, entry.getKey());
    // change the value for the key
    map.put(key, v2);

    iterator.remove();
    // This is weird, since the entry has in fact changed, so should not be removed, but JDK
    // implementations
    // all remove based on the key.
    postRemoveAsserts(map, key);
    assertFalse(map.containsValue(v1));
    assertFalse(map.containsValue(v2));
  }

  @Test
  public void valuesIteratorRemove() {
    installValue(map, key, v1);
    Iterator<String> iterator = map.values().iterator();
    while (iterator.hasNext()) {
      if (v1.equals(iterator.next())) {
        iterator.remove();
        break;
      }
    }
    postRemoveAsserts(map, key);
    assertFalse(map.containsValue(v1));
  }

  @Test
  public void valuesIteratorRemoveAfterValChange() {
    installValue(map, key, v1);
    Iterator<String> iterator = map.values().iterator();
    assertTrue(iterator.hasNext());
    String value = iterator.next();
    assertEquals(v1, value);

    // change the value for the key
    map.put(key, v2);

    iterator.remove();
    // This is weird, since the entry has in fact changed, so should not be removed, but JDK
    // implementations
    // all remove based on the key.
    postRemoveAsserts(map, key);
    assertFalse(map.containsValue(v1));
    assertFalse(map.containsValue(v2));
  }

  private void installValue(Map<Long, String> map, Long testKey, String value) {
    map.put(testKey, value);
    singleValueInMapAsserts(map, testKey, value);
  }

  private void singleValueInMapAsserts(Map<Long, String> map, Long testKey, String value) {
    assertEquals(value, map.get(testKey));
    assertEquals(1, map.size());
    assertFalse(map.isEmpty());
    assertTrue(map.containsKey(testKey));
    assertTrue(map.containsValue(value));
  }

  private void postRemoveAsserts(Map<Long, String> map, Long testKey) {
    assertNull(map.get(testKey));
    assertEquals(0, map.size());
    assertTrue(map.isEmpty());
    assertFalse(map.containsKey(testKey));
  }
}
