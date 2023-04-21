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

import static com.github.benmanes.caffeine.cache.Specifications.ASYNC_CACHE_LOADER_PARAM;
import static com.github.benmanes.caffeine.cache.Specifications.LOCAL_CACHE_FACTORY;
import static com.github.benmanes.caffeine.cache.Specifications.BOUNDED_LOCAL_CACHE;
import static com.github.benmanes.caffeine.cache.Specifications.BUILDER_PARAM;
import javax.lang.model.element.Modifier;
import com.squareup.javapoet.FieldSpec;

/**
 * Adds the constructor to the cache.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddConstructor extends LocalCacheRule {

  @Override
  protected boolean applies() {
    return true;
  }

  @Override
  protected void execute() {
    context.constructor
        .addParameter(BUILDER_PARAM)
        .addParameter(ASYNC_CACHE_LOADER_PARAM)
        .addParameter(boolean.class, "async");
    if (context.superClass.equals(BOUNDED_LOCAL_CACHE)) {
      context.suppressedWarnings.add("unchecked");
      context.constructor.addStatement(
          "super(builder, (AsyncCacheLoader<K, V>) cacheLoader, async)");
    } else {
      context.constructor.addStatement("super(builder, cacheLoader, async)");
    }
    context.cache
        .addField(FieldSpec.builder(LOCAL_CACHE_FACTORY, "FACTORY", Modifier.STATIC, Modifier.FINAL)
            .initializer("$N::new", context.className).build());
  }
}
