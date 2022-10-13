/*
 * Copyright (c) 2021 Goldman Sachs.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */
package com.github.benmanes.caffeine.eclipse.acceptance;

import java.io.Serializable;

/**
 * Ported from Eclipse Collections 11.0.
 */
@SuppressWarnings({"EqualsGetClass", "IdentityConversion"})
public class CollidingInt implements Serializable, Comparable<CollidingInt> {
  private static final long serialVersionUID = 1L;
  private final int value;
  private final int shift;

  public CollidingInt(int value, int shift) {
    this.shift = shift;
    this.value = value;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    CollidingInt that = (CollidingInt) o;

    return value == that.value && shift == that.shift;
  }

  @Override
  public int hashCode() {
    return value >> shift;
  }

  public int getValue() {
    return value;
  }

  @Override
  public int compareTo(CollidingInt o) {
    int result = Integer.valueOf(value).compareTo(o.value);
    if (result != 0) {
      return result;
    }
    return Integer.valueOf(shift).compareTo(o.shift);
  }
}
