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

import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.referenceType;

import java.util.List;

import javax.lang.model.element.Modifier;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;

/**
 * Adds the key to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AddKey extends NodeRule {

  @Override
  protected boolean applies() {
    return isBaseClass();
  }

  @Override
  protected void execute() {
    if (isStrongValues()) {
      addIfStrongValue();
    } else {
      addIfCollectedValue();
    }
  }

  private void addIfStrongValue() {
    var fieldSpec = isStrongKeys()
        ? FieldSpec.builder(kTypeVar, "key", Modifier.VOLATILE)
        : FieldSpec.builder(keyReferenceType(), "key", Modifier.VOLATILE);
    context.nodeSubtype
        .addField(fieldSpec.build())
        .addMethod(newGetter(keyStrength(), kTypeVar, "key", Visibility.PLAIN))
        .addMethod(newGetRef("key"));
    addVarHandle("key", isStrongKeys()
        ? ClassName.get(Object.class)
        : keyReferenceType().rawType);
  }

  private void addIfCollectedValue() {
    context.nodeSubtype.addMethod(MethodSpec.methodBuilder("getKeyReference")
        .addModifiers(context.publicFinalModifiers())
        .returns(Object.class)
        .addStatement("$1T valueRef = ($1T) $2L.get(this)",
            valueReferenceType(), varHandleName("value"))
        .addStatement("return valueRef.getKeyReference()")
        .build());

    var getKey = MethodSpec.methodBuilder("getKey")
        .addModifiers(context.publicFinalModifiers())
        .returns(kTypeVar)
        .addStatement("$1T valueRef = ($1T) $2L.get(this)",
            valueReferenceType(), varHandleName("value"));
    if (isStrongKeys()) {
      getKey.addStatement("return ($T) valueRef.getKeyReference()", kTypeVar);
    } else {
      getKey.addStatement("$1T keyRef = ($1T) valueRef.getKeyReference()", referenceType);
      getKey.addStatement("return keyRef.get()");
    }
    context.nodeSubtype.addMethod(getKey.build());
    context.suppressedWarnings.addAll(List.of("NullAway", "unchecked"));
  }
}
