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

import static com.github.benmanes.caffeine.cache.Specifications.vRefQueueType;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;
import static com.github.benmanes.caffeine.cache.node.NodeContext.varHandleName;

import java.lang.ref.Reference;
import java.util.Objects;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.node.NodeContext.Strength;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;

/**
 * Adds the value to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddValue implements NodeRule {

  @Override
  public boolean applies(NodeContext context) {
    return context.isBaseClass();
  }

  @Override
  public void execute(NodeContext context) {
    context.nodeSubtype
        .addField(newValueField(context))
        .addMethod(makeGetValue(context))
        .addMethod(context.newGetRef("value"))
        .addMethod(makeSetValue(context))
        .addMethod(makeContainsValue(context));
    if (context.isStrongValues()) {
      context.addVarHandle("value", ClassName.get(Object.class));
    } else {
      context.addVarHandle("value", context.valueReferenceType().rawType);
    }
  }

  private static FieldSpec newValueField(NodeContext context) {
    var fieldSpec = context.isStrongValues()
        ? FieldSpec.builder(vTypeVar, "value", Modifier.VOLATILE)
        : FieldSpec.builder(context.valueReferenceType(), "value", Modifier.VOLATILE);
    return fieldSpec.build();
  }

  /** Creates the getValue method. */
  private static MethodSpec makeGetValue(NodeContext context) {
    var getter = MethodSpec.methodBuilder("getValue")
        .addModifiers(context.publicFinalModifiers())
        .returns(vTypeVar);
    String handle = varHandleName("value");
    if (context.valueStrength() == Strength.STRONG) {
      getter.addStatement("return ($T) $L.get(this)", vTypeVar, handle);
      return getter.build();
    }

    var code = CodeBlock.builder()
        .beginControlFlow("for (;;)")
            .addStatement("$1T<V> ref = ($1T<V>) $2L.getOpaque(this)", Reference.class, handle)
            .addStatement("V referent = ref.get()")
            .beginControlFlow("if ((referent != null) || (ref == $L.getAcquire(this)))", handle)
                .addStatement("return referent")
            .endControlFlow()
        .endControlFlow()
        .build();
    return getter.addCode(code).build();
  }

  /** Creates the setValue method. */
  private static MethodSpec makeSetValue(NodeContext context) {
    var setter = MethodSpec.methodBuilder("setValue")
        .addModifiers(context.publicFinalModifiers())
        .addParameter(vTypeVar, "value")
        .addParameter(vRefQueueType, "referenceQueue");

    if (context.isStrongValues()) {
      setter.addStatement("$L.setRelease(this, $N)", varHandleName("value"), "value");
    } else {
      setter.addStatement("$1T<V> ref = ($1T<V>) $2L.get(this)",
          Reference.class, varHandleName("value"));
      setter.addStatement("$L.setRelease(this, new $T($L, $N, referenceQueue))",
          varHandleName("value"), context.valueReferenceType(),
          "getKeyReference()", "value");
      setter.addStatement("ref.clear()");
    }

    return setter.build();
  }

  private static MethodSpec makeContainsValue(NodeContext context) {
    var containsValue = MethodSpec.methodBuilder("containsValue")
        .addModifiers(context.publicFinalModifiers())
        .addParameter(Object.class, "value")
        .returns(boolean.class);
    if (context.isStrongValues()) {
      containsValue.addStatement("return $T.equals(value, getValue())", Objects.class);
    } else {
      containsValue.addStatement("return getValue() == value");
    }
    return containsValue.build();
  }
}
