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
package com.github.benmanes.caffeine.cache.local;

import java.util.function.Consumer;

import javax.lang.model.element.Modifier;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class LocalCacheRule implements Consumer<LocalCacheContext> {
  static final Modifier[] publicFinalModifiers =
    { Modifier.PUBLIC, Modifier.FINAL };
  static final Modifier[] protectedFinalModifiers =
    { Modifier.PROTECTED, Modifier.FINAL };
  static final Modifier[] privateFinalModifiers =
    { Modifier.PRIVATE, Modifier.FINAL };
  static final Modifier[] privateVolatileModifiers =
    { Modifier.PRIVATE, Modifier.VOLATILE };

  protected LocalCacheContext context;

  @Override
  public void accept(LocalCacheContext context) {
    this.context = context;
    if (applies()) {
      execute();
    }
  }

  /** @return if the rule should be executed. */
  protected abstract boolean applies();

  protected abstract void execute();
}
