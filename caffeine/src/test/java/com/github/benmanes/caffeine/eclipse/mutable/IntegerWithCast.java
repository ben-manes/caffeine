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

/**
 * Ported from Eclipse Collections 11.0.
 */
@SuppressWarnings({"all", "EqualsUnsafeCast"})
public final class IntegerWithCast {
  private final int value;

  public IntegerWithCast(int value) {
    this.value = value;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null) {
      return false;
    }

    IntegerWithCast that = (IntegerWithCast) o;
    return this.value == that.value;
  }

  @Override
  public int hashCode() {
    return this.value;
  }
}
