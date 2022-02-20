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
import org.eclipse.collections.impl.list.Interval;

/**
 * Ported from Eclipse Collections 11.0.
 */
public class NegativeIntervalFunction implements Function<Integer, Iterable<Integer>> {
  private static final long serialVersionUID = 1L;

  @Override
  public Iterable<Integer> valueOf(Integer object) {
    return Interval.fromTo(-1, -object);
  }
}
