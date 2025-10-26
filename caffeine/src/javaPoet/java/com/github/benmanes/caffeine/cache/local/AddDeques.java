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

import static com.github.benmanes.caffeine.cache.Specifications.ACCESS_ORDER_DEQUE;
import static com.github.benmanes.caffeine.cache.Specifications.WRITE_ORDER_DEQUE;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.github.benmanes.caffeine.cache.Rule;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddDeques implements Rule<LocalCacheContext> {

  @Override
  public boolean applies(LocalCacheContext context) {
    return true;
  }

  @Override
  public void execute(LocalCacheContext context) {
    addAccessOrderWindowDeque(context);
    addAccessOrderMainDeque(context);
    addWriteOrderDeque(context);
  }

  private static void addAccessOrderWindowDeque(LocalCacheContext context) {
    if (Feature.usesAccessOrderWindowDeque(context.parentFeatures)
        || !Feature.usesAccessOrderWindowDeque(context.generateFeatures)) {
      return;
    }

    context.constructor.addStatement(
        "this.$L = builder.evicts() || builder.expiresAfterAccess()\n? new $T()\n: null",
        "accessOrderWindowDeque", ACCESS_ORDER_DEQUE);
    addFieldAndMethod(context, ACCESS_ORDER_DEQUE, "accessOrderWindowDeque");
  }

  private static void addAccessOrderMainDeque(LocalCacheContext context) {
    if (Feature.usesAccessOrderMainDeque(context.parentFeatures)
        || !Feature.usesAccessOrderMainDeque(context.generateFeatures)) {
      return;
    }
    addDeque(context, ACCESS_ORDER_DEQUE, "accessOrderProbationDeque");
    addDeque(context, ACCESS_ORDER_DEQUE, "accessOrderProtectedDeque");
  }

  private static void addWriteOrderDeque(LocalCacheContext context) {
    if (Feature.usesWriteOrderDeque(context.parentFeatures)
        || !Feature.usesWriteOrderDeque(context.generateFeatures)) {
      return;
    }
    addDeque(context, WRITE_ORDER_DEQUE, "writeOrderDeque");
  }

  private static void addDeque(LocalCacheContext context, TypeName type, String name) {
    addConstructor(context, type, name);
    addFieldAndMethod(context, type, name);
  }

  private static void addConstructor(LocalCacheContext context, TypeName type, String name) {
    context.constructor.addStatement("this.$L = new $T()", name, type);
  }

  private static void addFieldAndMethod(LocalCacheContext context, TypeName type, String name) {
    context.classSpec.addField(FieldSpec.builder(type, name, Modifier.FINAL).build());
    context.classSpec.addMethod(MethodSpec.methodBuilder(name)
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return " + name)
        .returns(type)
        .build());
  }
}
