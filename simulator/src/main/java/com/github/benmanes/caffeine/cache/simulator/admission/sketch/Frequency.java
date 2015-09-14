/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.admission.sketch;

import javax.annotation.Nonnull;

/**
 * A multiset for estimating the popularity of an element.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public interface Frequency<E> {

  /** Increments the popularity of the element. */
  void increment(@Nonnull E e);

  /** Returns the estimated number of times the element was seen, up to an internal maximum. */
  int frequency(@Nonnull E e);
}
