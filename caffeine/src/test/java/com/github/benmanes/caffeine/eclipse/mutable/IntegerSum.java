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
 * A mutable sum which uses an int as the storage mechanism.
 *
 * @deprecated Don't use in new tests
 */
@Deprecated
@SuppressWarnings({"all", "EqualsBrokenForNull", "EqualsUnsafeCast"})
public final class IntegerSum implements Sum {
  private static final long serialVersionUID = 1L;

  private int sum = 0;

  public IntegerSum(int newSum) {
    this.sum = newSum;
  }

  @Override
  public Sum speciesNew() {
    return new IntegerSum(0);
  }

  @Override
  public Sum add(Object number) {
    return this.add((Number) number);
  }

  @Override
  public Sum add(Number number) {
    return this.add(number.intValue());
  }

  @Override
  public Sum add(Sum otherSum) {
    return this.add(otherSum.getValue());
  }

  @Override
  public Sum add(int value) {
    this.sum += value;
    return this;
  }

  public int getIntSum() {
    return this.sum;
  }

  @Override
  public Number getValue() {
    return this.sum;
  }

  @Override
  public boolean equals(Object o) {
    IntegerSum integerSum = (IntegerSum) o;
    return this.sum == integerSum.sum;
  }

  @Override
  public int hashCode() {
    return this.sum;
  }
}
