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

import java.lang.ref.Reference;
import java.util.Objects;

import javax.lang.model.element.Modifier;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;

/**
 * Adds the value to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AddValue extends NodeRule {

  @Override
  protected boolean applies() {
    return isBaseClass();
  }

  @Override
  protected void execute() {
    context.nodeSubtype
        .addField(newValueField())
        .addMethod(makeGetValue())
        .addMethod(newGetRef("value"))
        .addMethod(makeSetValue())
        .addMethod(makeContainsValue());
    if (isStrongValues()) {
      addVarHandle("value", ClassName.get(Object.class));
    } else {
      addVarHandle("value", valueReferenceType().rawType);
      context.suppressedWarnings.add("NullAway");
    }
  }

  private FieldSpec newValueField() {
    var fieldSpec = isStrongValues()
        ? FieldSpec.builder(vTypeVar, "value", Modifier.VOLATILE)
        : FieldSpec.builder(valueReferenceType(), "value", Modifier.VOLATILE);
    return fieldSpec.build();
  }

  /** Creates the getValue method. */
  private MethodSpec makeGetValue() {
    var getter = MethodSpec.methodBuilder("getValue")
        .addModifiers(context.publicFinalModifiers())
        .returns(vTypeVar);
    String handle = varHandleName("value");
    if (valueStrength() == Strength.STRONG) {
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
  private MethodSpec makeSetValue() {
    var setter = MethodSpec.methodBuilder("setValue")
        .addModifiers(context.publicFinalModifiers())
        .addParameter(vTypeVar, "value")
        .addParameter(vRefQueueType, "referenceQueue");

    if (isStrongValues()) {
      setter.addStatement("$L.setRelease(this, $N)", varHandleName("value"), "value");
    } else {
      setter.addStatement("$1T<V> ref = ($1T<V>) $2L.get(this)",
          Reference.class, varHandleName("value"));
      setter.addStatement("$L.setRelease(this, new $T($L, $N, referenceQueue))",
          varHandleName("value"), valueReferenceType(), "getKeyReference()", "value");
      setter.addStatement("ref.clear()");
    }

    return setter.build();
  }

  private MethodSpec makeContainsValue() {
    var containsValue = MethodSpec.methodBuilder("containsValue")
        .addModifiers(context.publicFinalModifiers())
        .addParameter(Object.class, "value")
        .returns(boolean.class);
    if (isStrongValues()) {
      containsValue.addStatement("return $T.equals(value, getValue())", Objects.class);
    } else {
      containsValue.addStatement("return getValue() == value");
    }
    return containsValue.build();
  }
}
