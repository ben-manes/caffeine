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
package com.github.benmanes.caffeine.cache.node;

import static com.github.benmanes.caffeine.cache.Specifications.UNSAFE_ACCESS;
import static com.github.benmanes.caffeine.cache.Specifications.newFieldOffset;
import static com.github.benmanes.caffeine.cache.Specifications.offsetName;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;

/**
 * Adds the expiration support to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddExpiration extends NodeRule {

  @Override
  protected boolean applies() {
    return true;
  }

  @Override
  protected void execute() {
    addAccessExpiration();
    addWriteExpiration();
    addRefreshExpiration();
  }

  private void addAccessExpiration() {
    if (!context.generateFeatures.contains(Feature.EXPIRE_ACCESS)) {
      return;
    }
    context.nodeSubtype.addField(newFieldOffset(context.className, "accessTime"))
        .addField(long.class, "accessTime", Modifier.PRIVATE, Modifier.VOLATILE)
        .addMethod(newGetter(Strength.STRONG, TypeName.LONG,
            "accessTime", Visibility.LAZY))
        .addMethod(newSetter(TypeName.LONG, "accessTime", Visibility.LAZY));
    addTimeConstructorAssignment(context.constructorByKey, "accessTime");
    addTimeConstructorAssignment(context.constructorByKeyRef, "accessTime");
  }

  private void addWriteExpiration() {
    if (!Feature.useWriteTime(context.parentFeatures)
        && Feature.useWriteTime(context.generateFeatures)) {
      context.nodeSubtype.addField(newFieldOffset(context.className, "writeTime"))
          .addField(long.class, "writeTime", Modifier.PRIVATE, Modifier.VOLATILE)
          .addMethod(newGetter(Strength.STRONG, TypeName.LONG, "writeTime", Visibility.LAZY))
          .addMethod(newSetter(TypeName.LONG, "writeTime", Visibility.LAZY));
      addTimeConstructorAssignment(context.constructorByKey, "writeTime");
      addTimeConstructorAssignment(context.constructorByKeyRef, "writeTime");
    }
  }

  private void addRefreshExpiration() {
    if (!context.generateFeatures.contains(Feature.REFRESH_WRITE)) {
      return;
    }
    context.nodeSubtype.addMethod(MethodSpec.methodBuilder("casWriteTime")
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .addParameter(long.class, "expect")
        .addParameter(long.class, "update")
        .returns(boolean.class)
        .addStatement("return $T.UNSAFE.compareAndSwapLong(this, $N, $N, $N)",
            UNSAFE_ACCESS, offsetName("writeTime"), "expect", "update")
        .build());
  }

  /** Adds a long constructor assignment. */
  private void addTimeConstructorAssignment(MethodSpec.Builder constructor, String field) {
    constructor.addStatement("$T.UNSAFE.putLong(this, $N, $N)",
        UNSAFE_ACCESS, offsetName(field), "now");
  }
}
