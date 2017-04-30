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

import static com.github.benmanes.caffeine.cache.Specifications.PACKAGE_NAME;
import static com.github.benmanes.caffeine.cache.Specifications.UNSAFE_ACCESS;
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.newFieldOffset;
import static com.github.benmanes.caffeine.cache.Specifications.offsetName;
import static com.google.common.base.Preconditions.checkState;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;

/**
 * Adds the key to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddKey extends NodeRule {

  @Override
  protected boolean applies() {
    return isBaseClass();
  }

  @Override
  protected void execute() {
    context.nodeSubtype
        .addField(newFieldOffset(context.className, "key"))
        .addField(newKeyField())
        .addMethod(newGetter(keyStrength(), kTypeVar, "key", Visibility.LAZY))
        .addMethod(newGetRef("key"));
    addKeyConstructorAssignment(context.constructorByKey, /* isReference */ false);
    addKeyConstructorAssignment(context.constructorByKeyRef, /* isReference */ true);
  }

  private FieldSpec newKeyField() {
    FieldSpec.Builder fieldSpec = isStrongKeys()
        ? FieldSpec.builder(kTypeVar, "key", Modifier.VOLATILE)
        : FieldSpec.builder(keyReferenceType(), "key", Modifier.VOLATILE);
    return fieldSpec.build();
  }

  private boolean isStrongKeys() {
    return context.parentFeatures.contains(Feature.STRONG_KEYS)
        || context.generateFeatures.contains(Feature.STRONG_KEYS);
  }

  private TypeName keyReferenceType() {
    checkState(context.generateFeatures.contains(Feature.WEAK_KEYS));
    return ParameterizedTypeName.get(
        ClassName.get(PACKAGE_NAME + ".References", "WeakKeyReference"), kTypeVar);
  }

  /** Adds a constructor assignment. */
  private void addKeyConstructorAssignment(MethodSpec.Builder constructor, boolean isReference) {
    if (isReference || isStrongKeys()) {
      String refAssignment = isStrongKeys()
          ? "(K) keyReference"
          : "(WeakKeyReference<K>) keyReference";
      constructor.addStatement("$T.UNSAFE.putObject(this, $N, $N)",
          UNSAFE_ACCESS, offsetName("key"), isReference ? refAssignment : "key");
    } else {
      constructor.addStatement("$T.UNSAFE.putObject(this, $N, new $T($N, $N))",
          UNSAFE_ACCESS, offsetName("key"), keyReferenceType(), "key", "keyReferenceQueue");
    }
  }
}
