/*
 * Copyright (c) 2021 Goldman Sachs.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */
package com.github.benmanes.caffeine.eclipse.mutable;

import static org.eclipse.collections.impl.factory.Iterables.iBag;
import static org.eclipse.collections.impl.factory.Iterables.iSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.collections.api.BooleanIterable;
import org.eclipse.collections.api.ByteIterable;
import org.eclipse.collections.api.CharIterable;
import org.eclipse.collections.api.DoubleIterable;
import org.eclipse.collections.api.FloatIterable;
import org.eclipse.collections.api.IntIterable;
import org.eclipse.collections.api.LazyIterable;
import org.eclipse.collections.api.LongIterable;
import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.ShortIterable;
import org.eclipse.collections.api.bag.Bag;
import org.eclipse.collections.api.bag.MutableBag;
import org.eclipse.collections.api.bag.sorted.MutableSortedBag;
import org.eclipse.collections.api.block.function.Function;
import org.eclipse.collections.api.block.function.Function0;
import org.eclipse.collections.api.block.function.Function2;
import org.eclipse.collections.api.block.function.primitive.CharFunction;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MapIterable;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.map.primitive.ObjectDoubleMap;
import org.eclipse.collections.api.map.primitive.ObjectLongMap;
import org.eclipse.collections.api.multimap.Multimap;
import org.eclipse.collections.api.multimap.MutableMultimap;
import org.eclipse.collections.api.multimap.set.MutableSetMultimap;
import org.eclipse.collections.api.partition.PartitionIterable;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.set.sorted.MutableSortedSet;
import org.eclipse.collections.api.tuple.Pair;
import org.eclipse.collections.impl.bag.mutable.HashBag;
import org.eclipse.collections.impl.bag.mutable.primitive.BooleanHashBag;
import org.eclipse.collections.impl.bag.mutable.primitive.ByteHashBag;
import org.eclipse.collections.impl.bag.mutable.primitive.CharHashBag;
import org.eclipse.collections.impl.bag.mutable.primitive.DoubleHashBag;
import org.eclipse.collections.impl.bag.mutable.primitive.FloatHashBag;
import org.eclipse.collections.impl.bag.mutable.primitive.IntHashBag;
import org.eclipse.collections.impl.bag.mutable.primitive.LongHashBag;
import org.eclipse.collections.impl.bag.mutable.primitive.ShortHashBag;
import org.eclipse.collections.impl.bag.sorted.mutable.TreeBag;
import org.eclipse.collections.impl.block.factory.Comparators;
import org.eclipse.collections.impl.block.factory.Functions;
import org.eclipse.collections.impl.block.factory.IntegerPredicates;
import org.eclipse.collections.impl.block.factory.Predicates;
import org.eclipse.collections.impl.block.factory.Predicates2;
import org.eclipse.collections.impl.block.factory.StringFunctions;
import org.eclipse.collections.impl.block.function.AddFunction;
import org.eclipse.collections.impl.block.function.PassThruFunction0;
import org.eclipse.collections.impl.block.procedure.CollectionAddProcedure;
import org.eclipse.collections.impl.factory.Bags;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.Maps;
import org.eclipse.collections.impl.factory.Sets;
import org.eclipse.collections.impl.list.Interval;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.list.primitive.IntInterval;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.eclipse.collections.impl.multimap.list.FastListMultimap;
import org.eclipse.collections.impl.multimap.set.UnifiedSetMultimap;
import org.eclipse.collections.impl.set.mutable.UnifiedSet;
import org.eclipse.collections.impl.set.sorted.mutable.TreeSortedSet;
import org.eclipse.collections.impl.string.immutable.CharAdapter;
import org.eclipse.collections.impl.test.Verify;
import org.eclipse.collections.impl.tuple.Tuples;
import org.junit.jupiter.api.Test;

/**
 * Ported from Eclipse Collections 11.0.
 */
@SuppressWarnings({"all", "deprecation", "IdentityConversion", "IsInstanceIncompatibleType",
    "NaturalOrder", "PreferredInterfaceType", "ReverseOrder", "unchecked", "YodaCondition"})
abstract class MapIterableTestCase extends CaffeineMutableMapTestCase {

  @Test
  void stream() {
    MapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    assertEquals("123",
        CharAdapter.adapt(map.stream().reduce("", (r, s) -> r + s)).toSortedList().makeString(""));
    assertEquals(map.reduce((r, s) -> r + s), map.stream().reduce((r, s) -> r + s));
  }

  @Test
  void parallelStream() {
    MapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    assertEquals("123",
        CharAdapter.adapt(map.stream().reduce("", (r, s) -> r + s)).toSortedList().makeString(""));
    assertEquals(map.reduce((r, s) -> r + s), map.stream().reduce((r, s) -> r + s));
  }

  @Test
  void equalsAndHashCode() {
    MapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    Verify.assertEqualsAndHashCode(Maps.mutable.of(1, "1", 2, "2", 3, "3"), map);
    Verify.assertEqualsAndHashCode(Maps.immutable.of(1, "1", 2, "2", 3, "3"), map);

    assertNotEquals(map, newMapWithKeysValues(1, "1", 2, "2"));
    assertNotEquals(map, newMapWithKeysValues(1, "1", 2, "2", 3, "3", 4, "4"));
    assertNotEquals(map, newMapWithKeysValues(1, "1", 2, "2", 4, "4"));
  }

  @Test
  void isEmpty() {
    assertFalse(newMapWithKeysValues(1, "1", 2, "2").isEmpty());
    assertTrue(newMap().isEmpty());
  }

  @Test
  void notEmpty() {
    assertFalse(newMap().notEmpty());
    assertTrue(newMapWithKeysValues(1, "1", 2, "2").notEmpty());
  }

  @Test
  void ifPresentApply() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2);
    assertEquals("1", map.ifPresentApply("1", String::valueOf));
    assertNull(map.ifPresentApply("3", String::valueOf));
  }

  @Test
  void getIfAbsent_function() {
    MapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    assertNull(map.get(4));
    assertEquals("1", map.getIfAbsent(1, new PassThruFunction0<>("4")));
    assertEquals("4", map.getIfAbsent(4, new PassThruFunction0<>("4")));
    assertEquals("3", map.getIfAbsent(3, new PassThruFunction0<>("3")));
    assertNull(map.get(4));
  }

  @Test
  void getOrDefault() {
    MapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    assertNull(map.get(4));
    assertEquals("1", map.getOrDefault(1, "4"));
    assertEquals("4", map.getOrDefault(4, "4"));
    assertEquals("3", map.getOrDefault(3, "3"));
    assertNull(map.get(4));
  }

  @Test
  void getIfAbsent() {
    MapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    assertNull(map.get(4));
    assertEquals("1", map.getIfAbsentValue(1, "4"));
    assertEquals("4", map.getIfAbsentValue(4, "4"));
    assertEquals("3", map.getIfAbsentValue(3, "3"));
    assertNull(map.get(4));
  }

  @Test
  void getIfAbsentWith() {
    MapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    assertNull(map.get(4));
    assertEquals("1", map.getIfAbsentWith(1, String::valueOf, 4));
    assertEquals("4", map.getIfAbsentWith(4, String::valueOf, 4));
    assertEquals("3", map.getIfAbsentWith(3, String::valueOf, 3));
    assertNull(map.get(4));
  }

  @Test
  void tap() {
    MutableList<String> tapResult = Lists.mutable.of();
    MapIterable<Integer, String> map =
        newMapWithKeysValues(1, "One", 2, "Two", 3, "Three", 4, "Four");
    assertSame(map, map.tap(tapResult::add));
    assertEquals(tapResult.toList(), tapResult);
  }

  @Test
  void forEach() {
    MutableBag<String> result = Bags.mutable.of();
    MapIterable<Integer, String> map =
        newMapWithKeysValues(1, "One", 2, "Two", 3, "Three", 4, "Four");
    map.forEach(CollectionAddProcedure.on(result));
    assertEquals(Bags.mutable.of("One", "Two", "Three", "Four"), result);
  }

  @Test
  void forEachWith() {
    MutableList<Integer> result = Lists.mutable.of();
    MapIterable<Integer, Integer> map = newMapWithKeysValues(-1, 1, -2, 2, -3, 3, -4, 4);
    map.forEachWith((argument1, argument2) -> result.add(argument1 + argument2), 10);
    Verify.assertSize(4, result);
    Verify.assertContainsAll(result, 11, 12, 13, 14);
  }

  @Test
  void forEachWithIndex() {
    MutableList<String> result = Lists.mutable.of();
    MapIterable<Integer, String> map =
        newMapWithKeysValues(1, "One", 2, "Two", 3, "Three", 4, "Four");
    map.forEachWithIndex((value, index) -> {
      result.add(value);
      result.add(String.valueOf(index));
    });
    Verify.assertSize(8, result);
    Verify.assertContainsAll(result, "One", "Two", "Three", "Four", // Map values
        "0", "1", "2", "3"); // Stringified index values
  }

  @Test
  void forEachKey() {
    UnifiedSet<Integer> result = UnifiedSet.newSet();
    MapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    map.forEachKey(CollectionAddProcedure.on(result));
    Verify.assertSetsEqual(UnifiedSet.newSetWith(1, 2, 3), result);
  }

  @Test
  void forEachValue() {
    UnifiedSet<String> result = UnifiedSet.newSet();
    MapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    map.forEachValue(CollectionAddProcedure.on(result));
    Verify.assertSetsEqual(UnifiedSet.newSetWith("1", "2", "3"), result);
  }

  @Test
  void forEachKeyValue() {
    UnifiedMap<Integer, String> result = UnifiedMap.newMap();
    MapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    map.forEachKeyValue(result::put);
    assertEquals(UnifiedMap.newWithKeysValues(1, "1", 2, "2", 3, "3"), result);

    MutableBag<String> result2 = Bags.mutable.of();
    MapIterable<Integer, String> map2 = newMapWithKeysValues(1, "One", 2, "Two", 3, "Three");
    map2.forEachKeyValue((key, value) -> result2.add(key + value));
    assertEquals(Bags.mutable.of("1One", "2Two", "3Three"), result2);
  }

  @Test
  void flipUniqueValues() {
    MapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    MapIterable<String, Integer> result = map.flipUniqueValues();
    assertEquals(UnifiedMap.newWithKeysValues("1", 1, "2", 2, "3", 3), result);

    assertThrows(IllegalStateException.class,
        () -> newMapWithKeysValues(1, "2", 2, "2").flipUniqueValues());
  }

  @Test
  void collectMap() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");
    MapIterable<Integer, String> actual = map.collect(
        (Function2<String, String, Pair<Integer, String>>) (argument1, argument2) -> Tuples.pair(
            Integer.valueOf(argument1), argument1 + ':' + new StringBuilder(argument2).reverse()));
    assertEquals(UnifiedMap.newWithKeysValues(1, "1:enO", 2, "2:owT", 3, "3:eerhT"), actual);
  }

  @Test
  void collectBoolean() {
    MapIterable<String, String> map =
        newMapWithKeysValues("One", "true", "Two", "nah", "Three", "TrUe");
    BooleanIterable actual = map.collectBoolean(Boolean::parseBoolean);
    assertEquals(BooleanHashBag.newBagWith(true, false, true), actual.toBag());
  }

  @Test
  void collectBooleanWithTarget() {
    BooleanHashBag target = new BooleanHashBag();
    MapIterable<String, String> map =
        newMapWithKeysValues("One", "true", "Two", "nah", "Three", "TrUe");
    BooleanHashBag result = map.collectBoolean(Boolean::parseBoolean, target);
    assertSame(target, result, "Target sent as parameter not returned");
    assertEquals(BooleanHashBag.newBagWith(true, false, true), result.toBag());
  }

  @Test
  void collectByte() {
    MapIterable<String, String> map = newMapWithKeysValues("One", "1", "Two", "2", "Three", "3");
    ByteIterable actual = map.collectByte(Byte::parseByte);
    assertEquals(ByteHashBag.newBagWith((byte) 1, (byte) 2, (byte) 3), actual.toBag());
  }

  @Test
  void collectByteWithTarget() {
    ByteHashBag target = new ByteHashBag();
    MapIterable<String, String> map = newMapWithKeysValues("One", "1", "Two", "2", "Three", "3");
    ByteHashBag result = map.collectByte(Byte::parseByte, target);
    assertSame(target, result, "Target sent as parameter not returned");
    assertEquals(ByteHashBag.newBagWith((byte) 1, (byte) 2, (byte) 3), result.toBag());
  }

  @Test
  void collectChar() {
    MapIterable<String, String> map =
        newMapWithKeysValues("One", "A1", "Two", "B", "Three", "C#++");
    CharIterable actual = map.collectChar((CharFunction<String>) string -> string.charAt(0));
    assertEquals(CharHashBag.newBagWith('A', 'B', 'C'), actual.toBag());
  }

  @Test
  void collectCharWithTarget() {
    CharHashBag target = new CharHashBag();
    MapIterable<String, String> map =
        newMapWithKeysValues("One", "A1", "Two", "B", "Three", "C#++");
    CharHashBag result = map.collectChar((CharFunction<String>) string -> string.charAt(0), target);
    assertSame(target, result, "Target sent as parameter not returned");
    assertEquals(CharHashBag.newBagWith('A', 'B', 'C'), result.toBag());
  }

  @Test
  void collectDouble() {
    MapIterable<String, String> map = newMapWithKeysValues("One", "1", "Two", "2", "Three", "3");
    DoubleIterable actual = map.collectDouble(Double::parseDouble);
    assertEquals(DoubleHashBag.newBagWith(1.0d, 2.0d, 3.0d), actual.toBag());
  }

  @Test
  void collectDoubleWithTarget() {
    DoubleHashBag target = new DoubleHashBag();
    MapIterable<String, String> map = newMapWithKeysValues("One", "1", "Two", "2", "Three", "3");
    DoubleHashBag result = map.collectDouble(Double::parseDouble, target);
    assertSame(target, result, "Target sent as parameter not returned");
    assertEquals(DoubleHashBag.newBagWith(1.0d, 2.0d, 3.0d), result.toBag());
  }

  @Test
  void collectFloat() {
    MapIterable<String, String> map = newMapWithKeysValues("One", "1", "Two", "2", "Three", "3");
    FloatIterable actual = map.collectFloat(Float::parseFloat);
    assertEquals(FloatHashBag.newBagWith(1.0f, 2.0f, 3.0f), actual.toBag());
  }

  @Test
  void collectFloatWithTarget() {
    FloatHashBag target = new FloatHashBag();
    MapIterable<String, String> map = newMapWithKeysValues("One", "1", "Two", "2", "Three", "3");
    FloatHashBag result = map.collectFloat(Float::parseFloat, target);
    assertSame(target, result, "Target sent as parameter not returned");
    assertEquals(FloatHashBag.newBagWith(1.0f, 2.0f, 3.0f), result.toBag());
  }

  @Test
  void collectInt() {
    MapIterable<String, String> map = newMapWithKeysValues("One", "1", "Two", "2", "Three", "3");
    IntIterable actual = map.collectInt(Integer::parseInt);
    assertEquals(IntHashBag.newBagWith(1, 2, 3), actual.toBag());
  }

  @Test
  void collectIntWithTarget() {
    IntHashBag target = new IntHashBag();
    MapIterable<String, String> map = newMapWithKeysValues("One", "1", "Two", "2", "Three", "3");
    IntHashBag result = map.collectInt(Integer::parseInt, target);
    assertSame(target, result, "Target sent as parameter not returned");
    assertEquals(IntHashBag.newBagWith(1, 2, 3), result.toBag());
  }

  @Test
  void collectLong() {
    MapIterable<String, String> map = newMapWithKeysValues("One", "1", "Two", "2", "Three", "3");
    LongIterable actual = map.collectLong(Long::parseLong);
    assertEquals(LongHashBag.newBagWith(1L, 2L, 3L), actual.toBag());
  }

  @Test
  void collectLongWithTarget() {
    LongHashBag target = new LongHashBag();
    MapIterable<String, String> map = newMapWithKeysValues("One", "1", "Two", "2", "Three", "3");
    LongHashBag result = map.collectLong(Long::parseLong, target);
    assertSame(target, result, "Target sent as parameter not returned");
    assertEquals(LongHashBag.newBagWith(1L, 2L, 3L), result.toBag());
  }

  @Test
  void collectShort() {
    MapIterable<String, String> map = newMapWithKeysValues("One", "1", "Two", "2", "Three", "3");
    ShortIterable actual = map.collectShort(Short::parseShort);
    assertEquals(ShortHashBag.newBagWith((short) 1, (short) 2, (short) 3), actual.toBag());
  }

  @Test
  void collectShortWithTarget() {
    ShortHashBag target = new ShortHashBag();
    MapIterable<String, String> map = newMapWithKeysValues("One", "1", "Two", "2", "Three", "3");
    ShortHashBag result = map.collectShort(Short::parseShort, target);
    assertSame(target, result, "Target sent as parameter not returned");
    assertEquals(ShortHashBag.newBagWith((short) 1, (short) 2, (short) 3), result.toBag());
  }

  @Test
  void collectValues() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");
    MapIterable<String, String> actual = map
        .collectValues((argument1, argument2) -> new StringBuilder(argument2).reverse().toString());
    assertEquals(UnifiedMap.newWithKeysValues("1", "enO", "2", "owT", "3", "eerhT"), actual);
  }

  @Test
  void select() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");
    RichIterable<String> actual = map.select("Two"::equals);
    assertEquals(HashBag.newBagWith("Two"), actual.toBag());
  }

  @Test
  void selectWith() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");
    RichIterable<String> actual = map.selectWith(Object::equals, "Two");
    assertEquals(HashBag.newBagWith("Two"), actual.toBag());
  }

  @Test
  void reject() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");
    RichIterable<String> actual = map.reject("Two"::equals);
    assertEquals(HashBag.newBagWith("One", "Three"), actual.toBag());
  }

  @Test
  void rejectWith() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");
    RichIterable<String> actual = map.rejectWith(Object::equals, "Two");
    assertEquals(HashBag.newBagWith("One", "Three"), actual.toBag());
  }

  @Test
  void collect() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");
    RichIterable<String> actual = map.collect(StringFunctions.toLowerCase());
    assertEquals(HashBag.newBagWith("one", "two", "three"), actual.toBag());
  }

  @Test
  void flatCollect() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "1", "2", "2", "3", "3", "4", "4");
    Function<String, MutableList<String>> function = Lists.mutable::with;

    Verify.assertListsEqual(Lists.mutable.with("1", "2", "3", "4"),
        map.flatCollect(function).toSortedList());

    Verify.assertSetsEqual(UnifiedSet.newSetWith("1", "2", "3", "4"),
        map.flatCollect(function, UnifiedSet.newSet()));
  }

  @Test
  void flatCollectWith() {
    MapIterable<String, Integer> map = newMapWithKeysValues("4", 4, "5", 5, "6", 6, "7", 7);

    Verify.assertSetsEqual(Sets.mutable.with(1, 2, 3, 4, 5, 6, 7),
        map.flatCollectWith(Interval::fromTo, 1).toSet());

    Verify.assertBagsEqual(
        Bags.mutable.with(4, 3, 2, 1, 5, 4, 3, 2, 1, 6, 5, 4, 3, 2, 1, 7, 6, 5, 4, 3, 2, 1),
        map.flatCollectWith(Interval::fromTo, 1, Bags.mutable.empty()));
  }

  @Test
  void selectMap() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");
    MapIterable<String, String> actual =
        map.select((argument1, argument2) -> "1".equals(argument1) || "Two".equals(argument2));
    assertEquals(2, actual.size());
    assertTrue(actual.keysView().containsAllArguments("1", "2"));
    assertTrue(actual.valuesView().containsAllArguments("One", "Two"));
  }

  @Test
  void rejectMap() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");
    MapIterable<String, String> actual =
        map.reject((argument1, argument2) -> "1".equals(argument1) || "Two".equals(argument2));
    assertEquals(UnifiedMap.newWithKeysValues("3", "Three"), actual);
  }

  @Test
  void flip() {
    Verify.assertEmpty(newMap().flip());

    MutableSetMultimap<String, String> expected = UnifiedSetMultimap.newMultimap();
    expected.put("odd", "One");
    expected.put("even", "Two");
    expected.put("odd", "Three");
    expected.put("even", "Four");

    assertEquals(expected, this
        .newMapWithKeysValues("One", "odd", "Two", "even", "Three", "odd", "Four", "even").flip());
  }

  @Test
  void detect() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");
    Pair<String, String> one = map.detect((argument1, argument2) -> "1".equals(argument1));
    assertNotNull(one);
    assertEquals("1", one.getOne());
    assertEquals("One", one.getTwo());

    Pair<String, String> two = map.detect((argument1, argument2) -> "Two".equals(argument2));
    assertNotNull(two);
    assertEquals("2", two.getOne());
    assertEquals("Two", two.getTwo());

    assertNull(map.detect((ignored1, ignored2) -> false));
  }

  @Test
  void detectOptional() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");
    Pair<String, String> one =
        map.detectOptional((argument1, argument2) -> "1".equals(argument1)).orElseThrow();
    assertNotNull(one);
    assertEquals("1", one.getOne());
    assertEquals("One", one.getTwo());

    Pair<String, String> two =
        map.detectOptional((argument1, argument2) -> "Two".equals(argument2)).orElseThrow();
    assertNotNull(two);
    assertEquals("2", two.getOne());
    assertEquals("Two", two.getTwo());

    assertFalse(map.detectOptional((ignored1, ignored2) -> false).isPresent());
  }

  @Test
  void anySatisfy() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    Verify.assertAnySatisfy((Map<String, String>) map, String.class::isInstance);
    assertFalse(map.anySatisfy("Monkey"::equals));
  }

  @Test
  void anySatisfyWith() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    assertTrue(map.anySatisfyWith(Predicates2.instanceOf(), String.class));
    assertFalse(map.anySatisfyWith(Object::equals, "Monkey"));
  }

  @Test
  void allSatisfy() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    Verify.assertAllSatisfy((Map<String, String>) map, String.class::isInstance);
    assertFalse(map.allSatisfy("Monkey"::equals));
  }

  @Test
  void allSatisfyWith() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    assertTrue(map.allSatisfyWith(Predicates2.instanceOf(), String.class));
    assertFalse(map.allSatisfyWith(Object::equals, "Monkey"));
  }

  @Test
  void noneSatisfy() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    Verify.assertNoneSatisfy((Map<String, String>) map, Integer.class::isInstance);
    assertTrue(map.noneSatisfy("Monkey"::equals));
    assertFalse(map.noneSatisfy("Two"::equals));
  }

  @Test
  void noneSatisfyWith() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    assertTrue(map.noneSatisfyWith(Predicates2.instanceOf(), Integer.class));
    assertTrue(map.noneSatisfyWith(Object::equals, "Monkey"));
    assertFalse(map.noneSatisfyWith(Object::equals, "Two"));
  }

  @Test
  void appendString() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    StringBuilder builder1 = new StringBuilder();
    map.appendString(builder1);
    String defaultString = builder1.toString();
    assertEquals(15, defaultString.length());

    StringBuilder builder2 = new StringBuilder();
    map.appendString(builder2, "|");
    String delimitedString = builder2.toString();
    assertEquals(13, delimitedString.length());
    Verify.assertContains("|", delimitedString);

    StringBuilder builder3 = new StringBuilder();
    map.appendString(builder3, "{", "|", "}");
    String wrappedString = builder3.toString();
    assertEquals(15, wrappedString.length());
    Verify.assertContains("|", wrappedString);
    assertTrue(wrappedString.startsWith("{"));
    assertTrue(wrappedString.endsWith("}"));
  }

  @Test
  void toBag() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    MutableBag<String> bag = map.toBag();
    assertEquals(Bags.mutable.of("One", "Two", "Three"), bag);
  }

  @Test
  void toSortedBag() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    MutableSortedBag<Integer> sorted = map.toSortedBag();
    Verify.assertSortedBagsEqual(TreeBag.newBagWith(1, 2, 3, 4), sorted);

    MutableSortedBag<Integer> reverse = map.toSortedBag(Collections.reverseOrder());
    Verify.assertSortedBagsEqual(TreeBag.newBagWith(Comparators.reverseNaturalOrder(), 1, 2, 3, 4),
        reverse);
  }

  @Test
  void toSortedBagBy() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    MutableSortedBag<Integer> sorted = map.toSortedBagBy(String::valueOf);
    Verify.assertSortedBagsEqual(TreeBag.newBagWith(1, 2, 3, 4), sorted);
  }

  @Test
  void asLazy() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    LazyIterable<String> lazy = map.asLazy();
    Verify.assertContainsAll(lazy.toList(), "One", "Two", "Three");
  }

  @Test
  void toList() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");
    MutableList<String> list = map.toList();
    Verify.assertContainsAll(list, "One", "Two", "Three");
  }

  @Test
  void toMap() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "3", "Three", "4", "Four");

    MapIterable<Integer, String> actual = map.toMap(String::length, String::valueOf);

    assertEquals(UnifiedMap.newWithKeysValues(3, "One", 5, "Three", 4, "Four"), actual);
  }

  @Test
  void toSet() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    MutableSet<String> set = map.toSet();
    Verify.assertContainsAll(set, "One", "Two", "Three");
  }

  @Test
  void toSortedList() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    MutableList<Integer> sorted = map.toSortedList();
    assertEquals(FastList.newListWith(1, 2, 3, 4), sorted);

    MutableList<Integer> reverse = map.toSortedList(Collections.reverseOrder());
    assertEquals(FastList.newListWith(4, 3, 2, 1), reverse);
  }

  @Test
  void toSortedListBy() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    MutableList<Integer> list = map.toSortedListBy(String::valueOf);
    assertEquals(FastList.newListWith(1, 2, 3, 4), list);
  }

  @Test
  void toSortedSet() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    MutableSortedSet<Integer> sorted = map.toSortedSet();
    Verify.assertSortedSetsEqual(TreeSortedSet.newSetWith(1, 2, 3, 4), sorted);

    MutableSortedSet<Integer> reverse = map.toSortedSet(Collections.reverseOrder());
    Verify.assertSortedSetsEqual(
        TreeSortedSet.newSetWith(Comparators.reverseNaturalOrder(), 1, 2, 3, 4), reverse);
  }

  @Test
  void toSortedSetBy() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    MutableSortedSet<Integer> sorted = map.toSortedSetBy(String::valueOf);
    Verify.assertSortedSetsEqual(TreeSortedSet.newSetWith(1, 2, 3, 4), sorted);
  }

  @Test
  void toSortedMap() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "3", "Three", "4", "Four");

    MapIterable<Integer, String> actual = map.toSortedMap(String::length, String::valueOf);

    MapIterable<Integer, String> actualWithComparator =
        map.toSortedMap(Comparators.reverseNaturalOrder(), String::length, String::valueOf);

    MapIterable<Integer, String> actualWithFunction =
        map.toSortedMapBy(key -> -key, String::length, String::valueOf);

    Verify.assertIterablesEqual(TreeSortedMap.newMapWith(3, "One", 5, "Three", 4, "Four"), actual);
    TreeSortedMap<Object, Object> expectedIterable =
        TreeSortedMap.newMap(Comparators.reverseNaturalOrder());
    expectedIterable.put(3, "One");
    expectedIterable.put(5, "Three");
    expectedIterable.put(4, "Four");
    Verify.assertIterablesEqual(expectedIterable, actualWithComparator);
    Verify.assertIterablesEqual(expectedIterable, actualWithFunction);
  }

  @Test
  void chunk() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    RichIterable<RichIterable<String>> chunks = map.chunk(2).toList();

    RichIterable<Integer> sizes = chunks.collect(RichIterable::size);
    assertEquals(FastList.newListWith(2, 1), sizes);
  }

  @Test
  void collect_value() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    Verify.assertContainsAll(map.collect(Functions.getToString()).toSet(), "1", "2", "3", "4");
    Verify.assertContainsAll(map.collect(String::valueOf, UnifiedSet.newSet()), "1", "2", "3", "4");
  }

  @Test
  void collectIf() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    Bag<String> odd = map.collectIf(IntegerPredicates.isOdd(), Functions.getToString()).toBag();
    assertEquals(Bags.mutable.of("1", "3"), odd);

    Bag<String> even = map.collectIf(IntegerPredicates.isEven(), String::valueOf, HashBag.newBag());
    assertEquals(Bags.mutable.of("2", "4"), even);
  }

  @Test
  void collectWith() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    RichIterable<Integer> actual = map.collectWith(AddFunction.INTEGER, 1);
    Verify.assertContainsAll(actual, 2, 3, 4, 5);
  }

  @Test
  void collectWithToTarget() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    FastList<Integer> actual = map.collectWith(AddFunction.INTEGER, 1, FastList.newList());
    Verify.assertContainsAll(actual, 2, 3, 4, 5);
  }

  @Test
  void contains() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    assertTrue(map.contains("Two"));
  }

  @Test
  void containsAll() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    assertTrue(map.containsAll(FastList.newListWith("One", "Two")));
    assertTrue(map.containsAll(FastList.newListWith("One", "Two", "Three")));
    assertFalse(map.containsAll(FastList.newListWith("One", "Two", "Three", "Four")));
  }

  @Test
  void containsKey() {
    MapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    assertTrue(map.containsKey(1));
    assertFalse(map.containsKey(4));
  }

  @Test
  void containsValue() {
    MapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    assertTrue(map.containsValue("1"));
    assertFalse(map.containsValue("4"));

    MapIterable<Integer, String> map2 = newMapWithKeysValues(3, "1", 2, "2", 1, "3");
    assertTrue(map2.containsValue("1"));
    assertFalse(map2.containsValue("4"));
  }

  @Test
  void getFirst() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    String value = map.getFirst();
    assertNotNull(value);
    assertTrue(map.valuesView().contains(value), value);

    assertNull(newMap().getFirst());
  }

  @Test
  void getLast() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    String value = map.getLast();
    assertNotNull(value);
    assertTrue(map.valuesView().contains(value), value);

    assertNull(newMap().getLast());
  }

  @Test
  void getOnly() {
    MapIterable<String, String> map = newMapWithKeyValue("1", "One");

    assertEquals("One", map.getOnly());
  }

  @Test
  void getOnly_throws_when_empty() {
    assertThrows(IllegalStateException.class, () -> newMap().getOnly());
  }

  @Test
  void getOnly_throws_when_multiple_values() {
    assertThrows(IllegalStateException.class, () ->
        newMapWithKeysValues("1", "One", "2", "Two").getOnly());
  }

  @Test
  void containsAllIterable() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    assertTrue(map.containsAllIterable(FastList.newListWith("One", "Two")));
    assertFalse(map.containsAllIterable(FastList.newListWith("One", "Four")));
  }

  @Test
  void containsAllArguments() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    assertTrue(map.containsAllArguments("One", "Two"));
    assertFalse(map.containsAllArguments("One", "Four"));
  }

  @Test
  void count() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    int actual = map.count(Predicates.or("One"::equals, "Three"::equals));

    assertEquals(2, actual);
  }

  @Test
  void countWith() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    int actual = map.countWith(Object::equals, "One");

    assertEquals(1, actual);
  }

  @Test
  void detect_value() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    String resultFound = map.detect("One"::equals);
    assertEquals("One", resultFound);

    String resultNotFound = map.detect("Four"::equals);
    assertNull(resultNotFound);
  }

  @Test
  void detectOptional_value() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    String resultFound = map.detectOptional("One"::equals).orElseThrow();
    assertEquals("One", resultFound);

    assertFalse(map.detectOptional("Four"::equals).isPresent());
  }

  @Test
  void detectWith() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    String resultFound = map.detectWith(Object::equals, "One");
    assertEquals("One", resultFound);

    String resultNotFound = map.detectWith(Object::equals, "Four");
    assertNull(resultNotFound);
  }

  @Test
  void detectWithOptional() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    String resultFound = map.detectWithOptional(Object::equals, "One").orElseThrow();
    assertEquals("One", resultFound);

    assertFalse(map.detectWithOptional(Object::equals, "Four").isPresent());
  }

  @Test
  void detectIfNone_value() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    String resultNotFound = map.detectIfNone("Four"::equals, () -> "Zero");
    assertEquals("Zero", resultNotFound);

    String resultFound = map.detectIfNone("One"::equals, () -> "Zero");
    assertEquals("One", resultFound);
  }

  @Test
  void detectWithIfNone() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    String resultNotFound = map.detectWithIfNone(Object::equals, "Four", () -> "Zero");
    assertEquals("Zero", resultNotFound);

    String resultFound = map.detectWithIfNone(Object::equals, "One", () -> "Zero");
    assertEquals("One", resultFound);
  }

  @Test
  void flatten_value() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two");

    Function<String, Iterable<Character>> function = object -> {
      MutableList<Character> result = Lists.mutable.of();
      char[] chars = object.toCharArray();
      for (char aChar : chars) {
        result.add(Character.valueOf(aChar));
      }
      return result;
    };

    RichIterable<Character> blob = map.flatCollect(function);
    assertTrue(blob.containsAllArguments(Character.valueOf('O'), Character.valueOf('n'),
        Character.valueOf('e'), Character.valueOf('T'), Character.valueOf('w'),
        Character.valueOf('o')));

    RichIterable<Character> blobFromTarget = map.flatCollect(function, FastList.newList());
    assertTrue(blobFromTarget.containsAllArguments(Character.valueOf('O'),
        Character.valueOf('n'), Character.valueOf('e'), Character.valueOf('T'),
        Character.valueOf('w'), Character.valueOf('o')));
  }

  /**
   * @since 9.0
   */
  @Test
  void countBy() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);
    Bag<Integer> evensAndOdds = map.countBy(each -> Integer.valueOf(each % 2));
    assertEquals(2, evensAndOdds.occurrencesOf(1));
    assertEquals(2, evensAndOdds.occurrencesOf(0));
    Bag<Integer> evensAndOdds2 =
        map.countBy(each -> Integer.valueOf(each % 2), Bags.mutable.empty());
    assertEquals(2, evensAndOdds2.occurrencesOf(1));
    assertEquals(2, evensAndOdds2.occurrencesOf(0));
  }

  /**
   * @since 9.0
   */
  @Test
  void countByWith() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);
    Bag<Integer> evensAndOdds = map.countByWith((each, parm) -> Integer.valueOf(each % parm), 2);
    assertEquals(2, evensAndOdds.occurrencesOf(1));
    assertEquals(2, evensAndOdds.occurrencesOf(0));
    Bag<Integer> evensAndOdds2 =
        map.countByWith((each, parm) -> Integer.valueOf(each % parm), 2, Bags.mutable.empty());
    assertEquals(2, evensAndOdds2.occurrencesOf(1));
    assertEquals(2, evensAndOdds2.occurrencesOf(0));
  }

  /**
   * @since 10.0.0
   */
  @Test
  void countByEach() {
    RichIterable<Integer> integerList = newMapWithKeysValues("1", 1, "2", 2, "4", 4);
    Bag<Integer> integerBag1 =
        integerList.countByEach(each -> IntInterval.oneTo(5).collect(i -> each * i));
    assertEquals(1, integerBag1.occurrencesOf(1));
    assertEquals(2, integerBag1.occurrencesOf(2));
    assertEquals(3, integerBag1.occurrencesOf(4));
    assertEquals(2, integerBag1.occurrencesOf(8));
    assertEquals(1, integerBag1.occurrencesOf(12));
    Bag<Integer> integerBag2 = integerList
        .countByEach(each -> IntInterval.oneTo(5).collect(i -> each * i), Bags.mutable.empty());
    assertEquals(1, integerBag2.occurrencesOf(1));
    assertEquals(2, integerBag2.occurrencesOf(2));
    assertEquals(3, integerBag2.occurrencesOf(4));
    assertEquals(2, integerBag2.occurrencesOf(8));
    assertEquals(1, integerBag2.occurrencesOf(12));
  }

  @Test
  void groupBy() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    Function<Integer, Boolean> isOddFunction = object -> IntegerPredicates.isOdd().accept(object);

    Multimap<Boolean, Integer> expected = FastListMultimap.newMultimap(Tuples.pair(Boolean.TRUE, 1),
        Tuples.pair(Boolean.TRUE, 3), Tuples.pair(Boolean.FALSE, 2), Tuples.pair(Boolean.FALSE, 4));

    Multimap<Boolean, Integer> actual = map.groupBy(isOddFunction);
    expected.forEachKey(each -> {
      assertTrue(actual.containsKey(each));
      MutableList<Integer> values = actual.get(each).toList();
      Verify.assertNotEmpty(values);
      assertTrue(expected.get(each).containsAllIterable(values));
    });

    Multimap<Boolean, Integer> actualFromTarget =
        map.groupBy(isOddFunction, FastListMultimap.newMultimap());
    expected.forEachKey(each -> {
      assertTrue(actualFromTarget.containsKey(each));
      MutableList<Integer> values = actualFromTarget.get(each).toList();
      Verify.assertNotEmpty(values);
      assertTrue(expected.get(each).containsAllIterable(values));
    });
  }

  @Test
  void groupByEach() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    MutableMultimap<Integer, Integer> expected = FastListMultimap.newMultimap();
    for (int i = 1; i < 4; i++) {
      expected.putAll(-i, Interval.fromTo(i, 4));
    }

    NegativeIntervalFunction function = new NegativeIntervalFunction();
    Multimap<Integer, Integer> actual = map.groupByEach(function);
    expected.forEachKey(each -> {
      assertTrue(actual.containsKey(each));
      MutableList<Integer> values = actual.get(each).toList();
      Verify.assertNotEmpty(values);
      assertTrue(expected.get(each).containsAllIterable(values));
    });

    Multimap<Integer, Integer> actualFromTarget =
        map.groupByEach(function, FastListMultimap.newMultimap());
    expected.forEachKey(each -> {
      assertTrue(actualFromTarget.containsKey(each));
      MutableList<Integer> values = actualFromTarget.get(each).toList();
      Verify.assertNotEmpty(values);
      assertTrue(expected.get(each).containsAllIterable(values));
    });
  }

  @Test
  void groupByUniqueKey() {
    MapIterable<Integer, Integer> map = newMapWithKeysValues(1, 1, 2, 2, 3, 3);
    assertEquals(UnifiedMap.newWithKeysValues(1, 1, 2, 2, 3, 3),
        map.groupByUniqueKey(id -> id));
  }

  @Test
  void groupByUniqueKey_throws() {
    assertThrows(IllegalStateException.class, () ->
        newMapWithKeysValues(1, 1, 2, 2, 3, 3).groupByUniqueKey(Functions.getFixedValue(1)));
  }

  @Test
  void groupByUniqueKey_target() {
    MapIterable<Integer, Integer> map = newMapWithKeysValues(1, 1, 2, 2, 3, 3);
    MutableMap<Integer, Integer> integers =
        map.groupByUniqueKey(id -> id, UnifiedMap.newWithKeysValues(0, 0));
    assertEquals(UnifiedMap.newWithKeysValues(0, 0, 1, 1, 2, 2, 3, 3), integers);
  }

  @Test
  void groupByUniqueKey_target_throws() {
    assertThrows(IllegalStateException.class, () -> {
      newMapWithKeysValues(1, 1, 2, 2, 3, 3).groupByUniqueKey(id -> id,
          UnifiedMap.newWithKeysValues(2, 2));
    });
  }

  @Test
  void injectInto() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    Integer actual = map.injectInto(0, AddFunction.INTEGER);
    assertEquals(Integer.valueOf(10), actual);

    Sum sum = map.injectInto(new IntegerSum(0), SumProcedure.number());
    assertEquals(new IntegerSum(10), sum);
  }

  @Test
  void injectIntoInt() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    int actual = map.injectInto(0, AddFunction.INTEGER_TO_INT);
    assertEquals(10, actual);
  }

  @Test
  void injectIntoLong() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    long actual = map.injectInto(0, AddFunction.INTEGER_TO_LONG);
    assertEquals(10, actual);
  }

  @Test
  void injectIntoFloat() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    float actual = map.injectInto(0, AddFunction.INTEGER_TO_FLOAT);
    assertEquals(10.0F, actual, 0.01);
  }

  @Test
  void injectIntoDouble() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    double actual = map.injectInto(0, AddFunction.INTEGER_TO_DOUBLE);
    assertEquals(10.0d, actual, 0.01);
  }

  @Test
  void sumOfInt() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    long actual = map.sumOfInt(integer -> integer);
    assertEquals(10L, actual);
  }

  @Test
  void sumOfLong() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    long actual = map.sumOfLong(Integer::longValue);
    assertEquals(10, actual);
  }

  @Test
  void testAggregateBy() {
    String oneToFive = "oneToFive";
    String sixToNine = "sixToNine";
    String tenToFifteen = "tenToFifteen";
    String sixteenToTwenty = "sixteenToTwenty";

    MapIterable<String, Interval> map =
        Maps.mutable.with(oneToFive, Interval.fromTo(1, 5), sixToNine, Interval.fromTo(6, 9),
            tenToFifteen, Interval.fromTo(10, 15), sixteenToTwenty, Interval.fromTo(16, 20));

    String lessThanTen = "lessThanTen";
    String greaterOrEqualsToTen = "greaterOrEqualsToTen";

    MapIterable<String, Long> result = map.aggregateBy(eachKey -> {
      return eachKey.equals(oneToFive) || eachKey.equals(sixToNine) ? lessThanTen
          : greaterOrEqualsToTen;
    }, each -> each.sumOfInt(Integer::intValue), () -> 0L,
        (argument1, argument2) -> argument1 + argument2);

    MapIterable<String, Long> expected =
        Maps.mutable.with(lessThanTen, Interval.fromTo(1, 9).sumOfInt(Integer::intValue),
            greaterOrEqualsToTen, Interval.fromTo(10, 20).sumOfInt(Integer::intValue));
    assertEquals(expected, result);
  }

  @Test
  void sumOfFloat() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    double actual = map.sumOfFloat(Integer::floatValue);
    assertEquals(10.0d, actual, 0.01);
  }

  @Test
  void sumOfDouble() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    double actual = map.sumOfDouble(Integer::doubleValue);
    assertEquals(10.0d, actual, 0.01);
  }

  @Test
  void sumByInt() {
    RichIterable<String> values = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    ObjectLongMap<Integer> result =
        values.sumByInt(s -> Integer.parseInt(s) % 2, Integer::parseInt);
    assertEquals(4, result.get(1));
    assertEquals(2, result.get(0));
  }

  @Test
  void sumByFloat() {
    RichIterable<String> values = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    ObjectDoubleMap<Integer> result =
        values.sumByFloat(s -> Integer.parseInt(s) % 2, Float::parseFloat);
    assertEquals(4.0f, result.get(1), 0.0);
    assertEquals(2.0f, result.get(0), 0.0);
  }

  @Test
  void sumByLong() {
    RichIterable<String> values = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    ObjectLongMap<Integer> result = values.sumByLong(s -> Integer.parseInt(s) % 2, Long::parseLong);
    assertEquals(4, result.get(1));
    assertEquals(2, result.get(0));
  }

  @Test
  void sumByDouble() {
    RichIterable<String> values = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    ObjectDoubleMap<Integer> result =
        values.sumByDouble(s -> Integer.parseInt(s) % 2, Double::parseDouble);
    assertEquals(4.0d, result.get(1), 0.0);
    assertEquals(2.0d, result.get(0), 0.0);
  }

  @Test
  void makeString() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    String defaultString = map.makeString();
    assertEquals(15, defaultString.length());

    String delimitedString = map.makeString("|");
    assertEquals(13, delimitedString.length());
    Verify.assertContains("|", delimitedString);

    String wrappedString = map.makeString("{", "|", "}");
    assertEquals(15, wrappedString.length());
    Verify.assertContains("|", wrappedString);
    assertTrue(wrappedString.startsWith("{"));
    assertTrue(wrappedString.endsWith("}"));
  }

  @Test
  void min() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    assertEquals(Integer.valueOf(1), map.min());
    assertEquals(Integer.valueOf(1), map.min(Integer::compareTo));
  }

  @Test
  void max() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    assertEquals(Integer.valueOf(4), map.max());
    assertEquals(Integer.valueOf(4), map.max(Integer::compareTo));
  }

  @Test
  void minBy() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    assertEquals(Integer.valueOf(1), map.minBy(String::valueOf));
  }

  @Test
  void maxBy() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    assertEquals(Integer.valueOf(4), map.maxBy(String::valueOf));
  }

  @Test
  void reject_value() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    Verify.assertContainsAll(map.reject(Predicates.lessThan(3)).toSet(), 3, 4);
    Verify.assertContainsAll(map.reject(Predicates.lessThan(3), UnifiedSet.newSet()), 3, 4);
  }

  @Test
  void rejectWith_value() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    Verify.assertContainsAll(map.rejectWith(Predicates2.lessThan(), 3, UnifiedSet.newSet()), 3, 4);
  }

  @Test
  void select_value() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    Verify.assertContainsAll(map.select(Predicates.lessThan(3)).toSet(), 1, 2);
    Verify.assertContainsAll(map.select(Predicates.lessThan(3), UnifiedSet.newSet()), 1, 2);
  }

  @Test
  void selectWith_value() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    Verify.assertContainsAll(map.selectWith(Predicates2.lessThan(), 3, UnifiedSet.newSet()), 1, 2);
  }

  @Test
  void partition_value() {
    MapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2, "C", 3, "D", 4);
    PartitionIterable<Integer> partition = map.partition(IntegerPredicates.isEven());
    assertEquals(iSet(4, 2), partition.getSelected().toSet());
    assertEquals(iSet(3, 1), partition.getRejected().toSet());
  }

  @Test
  void partitionWith_value() {
    MapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2, "C", 3, "D", 4);
    PartitionIterable<Integer> partition =
        map.partitionWith(Predicates2.in(), map.select(IntegerPredicates.isEven()));
    assertEquals(iSet(4, 2), partition.getSelected().toSet());
    assertEquals(iSet(3, 1), partition.getRejected().toSet());
  }

  @Test
  void selectInstancesOf_value() {
    MapIterable<String, Number> map = newMapWithKeysValues("1", 1, "2", 2.0, "3", 3, "4", 4.0);
    assertEquals(iBag(1, 3), map.selectInstancesOf(Integer.class).toBag());
  }

  @Test
  void toArray() {
    MapIterable<String, Integer> map = newMapWithKeysValues("1", 1, "2", 2, "3", 3, "4", 4);

    Object[] array = map.toArray();
    Verify.assertSize(4, array);
    Integer[] array2 = map.toArray(new Integer[0]);
    Verify.assertSize(4, array2);
    Integer[] array3 = map.toArray(new Integer[4]);
    Verify.assertSize(4, array3);
    Integer[] array4 = map.toArray(new Integer[5]);
    Verify.assertSize(5, array4);
  }

  @Test
  void zip() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    List<Object> nulls = Collections.nCopies(map.size(), null);
    List<Object> nullsPlusOne = Collections.nCopies(map.size() + 1, null);
    List<Object> nullsMinusOne = Collections.nCopies(map.size() - 1, null);

    RichIterable<Pair<String, Object>> pairs = map.zip(nulls);
    assertEquals(map.toSet(),
        pairs.collect((Function<Pair<String, ?>, String>) Pair::getOne).toSet());
    assertEquals(nulls,
        pairs.collect((Function<Pair<?, Object>, Object>) Pair::getTwo, Lists.mutable.of()));

    RichIterable<Pair<String, Object>> pairsPlusOne = map.zip(nullsPlusOne);
    assertEquals(map.toSet(),
        pairsPlusOne.collect((Function<Pair<String, ?>, String>) Pair::getOne).toSet());
    assertEquals(nulls,
        pairsPlusOne.collect((Function<Pair<?, Object>, Object>) Pair::getTwo, Lists.mutable.of()));

    RichIterable<Pair<String, Object>> pairsMinusOne = map.zip(nullsMinusOne);
    assertEquals(map.size() - 1, pairsMinusOne.size());
    assertTrue(map.valuesView().containsAllIterable(
        pairsMinusOne.collect((Function<Pair<String, ?>, String>) Pair::getOne).toSet()));

    assertEquals(map.zip(nulls).toSet(), map.zip(nulls, UnifiedSet.newSet()));
  }

  @Test
  void zipWithIndex() {
    MapIterable<String, String> map = newMapWithKeysValues("1", "One", "2", "Two", "3", "Three");

    RichIterable<Pair<String, Integer>> pairs = map.zipWithIndex();

    assertEquals(map.toSet(), pairs.collect(Pair::getOne).toSet());
    assertEquals(Interval.zeroTo(map.size() - 1).toSet(),
        pairs.collect(Pair::getTwo, UnifiedSet.newSet()));

    assertEquals(map.zipWithIndex().toSet(), map.zipWithIndex(UnifiedSet.newSet()));
  }

  @Test
  void aggregateByMutating() {
    Function0<AtomicInteger> valueCreator = AtomicInteger::new;
    RichIterable<Integer> collection = newMapWithKeysValues(1, 1, 2, 2, 3, 3);
    MapIterable<String, AtomicInteger> aggregation =
        collection.aggregateInPlaceBy(String::valueOf, valueCreator, AtomicInteger::addAndGet);
    assertEquals(1, aggregation.get("1").intValue());
    assertEquals(2, aggregation.get("2").intValue());
    assertEquals(3, aggregation.get("3").intValue());
  }

  @Test
  void aggregateByNonMutating() {
    Function0<Integer> valueCreator = () -> 0;
    Function2<Integer, Integer, Integer> sumAggregator =
        (integer1, integer2) -> integer1 + integer2;
    RichIterable<Integer> collection = newMapWithKeysValues(1, 1, 2, 2, 3, 3);
    MapIterable<String, Integer> aggregation =
        collection.aggregateBy(String::valueOf, valueCreator, sumAggregator);
    assertEquals(1, aggregation.get("1").intValue());
    assertEquals(2, aggregation.get("2").intValue());
    assertEquals(3, aggregation.get("3").intValue());
  }

  @Test
  void keyValuesView() {
    MapIterable<Integer, String> map = newMapWithKeysValues(1, "A", 2, "B", 3, "C", 4, "D");
    MutableSet<Pair<Integer, String>> keyValues = map.keyValuesView().toSet();
    assertEquals(UnifiedSet.newSetWith(Tuples.pair(1, "A"), Tuples.pair(2, "B"),
        Tuples.pair(3, "C"), Tuples.pair(4, "D")), keyValues);
  }

  @Test
  void nullCollisionWithCastInEquals() {
    if (newMap() instanceof SortedMap || newMap() instanceof ConcurrentMap) {
      return;
    }
    MapIterable<IntegerWithCast, String> mutableMap = newMapWithKeysValues(new IntegerWithCast(0),
        "Test 2", new IntegerWithCast(0), "Test 3", null, "Test 1");
    assertEquals(newMapWithKeysValues(new IntegerWithCast(0), "Test 3", null, "Test 1"),
        mutableMap);
    assertEquals("Test 3", mutableMap.get(new IntegerWithCast(0)));
    assertEquals("Test 1", mutableMap.get(null));
  }

  @Test
  void testNewMap() {
    MapIterable<Integer, Integer> map = newMap();
    Verify.assertEmpty(map);
    Verify.assertSize(0, map);
  }

  @Test
  void testNewMapWithKeyValue() {
    MapIterable<Integer, String> map = newMapWithKeyValue(1, "One");
    Verify.assertNotEmpty(map);
    Verify.assertSize(1, map);
    Verify.assertContainsKeyValue(1, "One", map);
  }

  @Test
  void newMapWithWith() {
    MapIterable<Integer, String> map = newMapWithKeysValues(1, "One", 2, "Two");
    Verify.assertNotEmpty(map);
    Verify.assertSize(2, map);
    Verify.assertContainsAllKeyValues(map, 1, "One", 2, "Two");
  }

  @Test
  void newMapWithWithWith() {
    MapIterable<Integer, String> map = newMapWithKeysValues(1, "One", 2, "Two", 3, "Three");
    Verify.assertNotEmpty(map);
    Verify.assertSize(3, map);
    Verify.assertContainsAllKeyValues(map, 1, "One", 2, "Two", 3, "Three");
  }

  @Test
  void newMapWithWithWithWith() {
    MapIterable<Integer, String> map =
        newMapWithKeysValues(1, "One", 2, "Two", 3, "Three", 4, "Four");
    Verify.assertNotEmpty(map);
    Verify.assertSize(4, map);
    Verify.assertContainsAllKeyValues(map, 1, "One", 2, "Two", 3, "Three", 4, "Four");
  }

  @Test
  void iterator() {
    MapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    Iterator<Integer> iterator = map.iterator();
    assertTrue(iterator.hasNext());
    int sum = 0;
    while (iterator.hasNext()) {
      sum += iterator.next();
    }
    assertFalse(iterator.hasNext());
    assertEquals(6, sum);
  }

  @Test
  void keysView() {
    MutableList<Integer> keys = newMapWithKeysValues(1, 1, 2, 2).keysView().toSortedList();
    assertEquals(FastList.newListWith(1, 2), keys);
  }

  @Test
  void valuesView() {
    MutableList<Integer> values = newMapWithKeysValues(1, 1, 2, 2).valuesView().toSortedList();
    assertEquals(FastList.newListWith(1, 2), values);
  }

  @Test
  void test_toString() {
    MapIterable<Integer, String> map = newMapWithKeysValues(1, "One", 2, "Two", 3, "Three");

    String stringToSearch = map.toString();
    Verify.assertContains("1=One", stringToSearch);
    Verify.assertContains("2=Two", stringToSearch);
    Verify.assertContains("3=Three", stringToSearch);
  }
}
