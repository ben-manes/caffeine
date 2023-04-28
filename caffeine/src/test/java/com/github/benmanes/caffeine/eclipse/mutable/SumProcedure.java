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

import org.eclipse.collections.api.block.function.Function;
import org.eclipse.collections.api.block.function.Function2;
import org.eclipse.collections.api.block.procedure.Procedure;
import org.eclipse.collections.impl.parallel.ProcedureFactory;

/**
 * A multi-purpose code block factory which can be used to summarize the elements of a collection
 * either via a forEach() or injectInto() call. SumProcedure returns optimized iterator blocks for
 * specialized iterator subclasses of Function which result in less garbage created for summing
 * iterator attributes of collections.
 *
 * @deprecated Don't use in new tests
 */
@Deprecated
@SuppressWarnings({"all", "overloads", "unchecked"})
public class SumProcedure<T>
    implements Procedure<T>, Function2<Sum, T, Sum>, ProcedureFactory<SumProcedure<T>> {
  private static final long serialVersionUID = 1L;
  private static final SumProcedure<?> NUMBER = new SumProcedure<>();

  protected final Sum sum;
  protected final Function<? super T, ? extends Number> function;

  public SumProcedure(Sum newSum) {
    this(newSum, null);
  }

  public SumProcedure() {
    this(null, null);
  }

  public SumProcedure(Sum newSum, Function<? super T, ? extends Number> function) {
    this.sum = newSum;
    this.function = function;
  }

  public static <T extends Number> SumProcedure<T> number() {
    return (SumProcedure<T>) NUMBER;
  }

  @Override
  public SumProcedure<T> create() {
    return new SumProcedure<>(this.sum.speciesNew(), this.function);
  }

  @Override
  public Sum value(Sum argument1, T argument2) {
    return argument1.add(argument2);
  }

  @Override
  @SuppressWarnings("CheckReturnValue")
  public void value(T object) {
    this.sum.add(object);
  }

  public Sum getSum() {
    return this.sum;
  }
}
