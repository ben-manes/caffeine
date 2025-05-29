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
import static com.github.benmanes.caffeine.cache.node.NodeContext.varHandleName;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.node.NodeContext.Strength;
import com.github.benmanes.caffeine.cache.node.NodeContext.Visibility;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;

/**
 * Adds the key to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddKey implements NodeRule {

  @Override
  public boolean applies(NodeContext context) {
    return context.isBaseClass();
  }

  @Override
  public void execute(NodeContext context) {
    if (context.isStrongValues()) {
      addIfStrongValue(context);
    } else {
      addIfCollectedValue(context);
    }
  }

  private static void addIfStrongValue(NodeContext context) {
    var fieldSpec = context.isStrongKeys()
        ? FieldSpec.builder(kTypeVar, "key", Modifier.VOLATILE)
        : FieldSpec.builder(context.keyReferenceType(), "key", Modifier.VOLATILE);
    context.nodeSubtype
        .addField(fieldSpec.build())
        .addMethod(context.newGetter(context.keyStrength(), kTypeVar, "key",
            (context.keyStrength() == Strength.STRONG) ? Visibility.OPAQUE : Visibility.PLAIN))
        .addMethod(newGetKeyRef(context));
    context.addVarHandle("key", context.isStrongKeys()
        ? ClassName.get(Object.class)
        : context.keyReferenceType().rawType());
  }

  private static void addIfCollectedValue(NodeContext context) {
    context.nodeSubtype.addMethod(MethodSpec.methodBuilder("getKeyReference")
        .addModifiers(context.publicFinalModifiers())
        .returns(Object.class)
        .addStatement("$1T valueRef = ($1T) $2L.getAcquire(this)",
            context.valueReferenceType(), varHandleName("value"))
        .addStatement("return valueRef.getKeyReference()")
        .build());

    var getKey = MethodSpec.methodBuilder("getKey")
        .addModifiers(context.publicFinalModifiers())
        .returns(kTypeVar)
        .addStatement("$1T valueRef = ($1T) $2L.getAcquire(this)",
            context.valueReferenceType(), varHandleName("value"));
    if (context.isStrongKeys()) {
      getKey.addStatement("return ($T) valueRef.getKeyReference()", kTypeVar);
    } else {
      getKey.addStatement("$1T keyRef = ($1T) valueRef.getKeyReference()", referenceType);
      getKey.addStatement("return keyRef.get()");
    }
    context.nodeSubtype.addMethod(getKey.build());
    context.suppressedWarnings.add("unchecked");
  }

  private static MethodSpec newGetKeyRef(NodeContext context) {
    var getter = MethodSpec.methodBuilder("getKeyReference")
        .addModifiers(context.publicFinalModifiers())
        .returns(Object.class);
    getter.addStatement("return $L.getOpaque(this)", varHandleName("key"));
    return getter.build();
  }
}
