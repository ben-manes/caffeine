/*
 * Copyright 2021 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.testing;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.google.errorprone.annotations.Immutable;

/**
 * An instance class for {@code int}. Unlike the {@code Integer}, this type is not a value class and
 * has instance identity. Like {@code Integer}, {@link #valueOf(int)} may cache the instance if the
 * value falls within a common range.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Immutable
public final class Int implements Serializable {
  public static final Int MAX_VALUE = Int.valueOf(Integer.MAX_VALUE);

  private static final long serialVersionUID = 1L;

  private static final int low = -2048;
  private static final int high = 2048;
  private static final Int[] cache = makeSharedCache();

  private final int value;

  /** Constructs a newly allocated {@code Int} object with the same {@code value}. */
  public Int(Int value) {
    this(value.value);
  }

  /**
   * Constructs a newly allocated {@code Int} object that represents the specified {@code int}
   * value.
   */
  public Int(int value) {
    this.value = value;
  }

  /** Returns the value of the specified number as an {@code int}. */
  public int intValue() {
    return value;
  }

  /** Returns the negated value, possibly cached. */
  public Int negate() {
    return valueOf(-value);
  }

  /** Returns the added value, possibly cached. */
  public Int add(int i) {
    return valueOf(value + i);
  }

  /** Returns the added value, possibly cached. */
  public Int add(Int i) {
    return add(i.value);
  }

  /** Returns a completed future of this value. */
  public CompletableFuture<Int> asFuture() {
    return CompletableFuture.completedFuture(this);
  }

  @Override
  public boolean equals(Object o) {
    return (o == this) || ((o instanceof Int) && (value == ((Int) o).value));
  }

  @Override
  public int hashCode() {
    return Integer.hashCode(value);
  }

  @Override
  public String toString() {
    return Integer.toString(value);
  }

  /**
   * Returns an {@code Int} instance representing the specified {@code int} value. This method will
   * always cache values in the range of {@code low} to {@code high} inclusively.
   */
  public static Int valueOf(int i) {
    return ((i >= low) && (i <= high)) ? cache[i - low] : new Int(i);
  }

  /** Returns a list of possibly cached values. */
  public static List<Int> listOf(int... values) {
    switch (values.length) {
      case 1: return List.of(valueOf(values[0]));
      case 2: return List.of(valueOf(values[0]), valueOf(values[1]));
      case 3: return List.of(valueOf(values[0]), valueOf(values[1]), valueOf(values[2]));
      case 4: return List.of(valueOf(values[0]), valueOf(values[1]),
          valueOf(values[2]), valueOf(values[3]));
      default:
        var list = new ArrayList<Int>(values.length);
        for (int value : values) {
          list.add(valueOf(value));
        }
        return list;
    }
  }

  /** Returns a set of possibly cached values. */
  public static Set<Int> setOf(int... values) {
    switch (values.length) {
      case 1: return Set.of(valueOf(values[0]));
      case 2: return Set.of(valueOf(values[0]), valueOf(values[1]));
      case 3: return Set.of(valueOf(values[0]), valueOf(values[1]), valueOf(values[2]));
      case 4: return Set.of(valueOf(values[0]), valueOf(values[1]),
          valueOf(values[2]), valueOf(values[3]));
      default:
        var set = new LinkedHashSet<Int>(values.length);
        for (int value : values) {
          set.add(valueOf(value));
        }
        return set;
    }
  }

  /** Returns a map of possibly cached values. */
  public static Map<Int, Int> mapOf(int... mappings) {
    checkArgument((mappings.length % 2) == 0);
    var map = new LinkedHashMap<Int, Int>(mappings.length / 2);
    for (int i = 0; i < mappings.length; i += 2) {
      map.put(valueOf(mappings[i]), valueOf(mappings[i + 1]));
    }
    return map;
  }

  /** Returns a completed future of the value, possibly cached. */
  public static CompletableFuture<Int> futureOf(int i) {
    return valueOf(i).asFuture();
  }

  /** Returns a preallocated range of {@code Int} instances */
  private static Int[] makeSharedCache() {
    var array = new Int[high - low + 1];
    for (int i = 0; i < array.length; i++) {
      array[i] = new Int(i + low);
    }
    return array;
  }
}
