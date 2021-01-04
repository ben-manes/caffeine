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

import static com.github.benmanes.caffeine.cache.Specifications.NODE;
import static org.apache.commons.lang3.StringUtils.capitalize;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;

/**
 * Adds the expiration support to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AddExpiration extends NodeRule {

  @Override
  protected boolean applies() {
    return true;
  }

  @Override
  protected void execute() {
    addVariableExpiration();
    addAccessExpiration();
    addWriteExpiration();
    addRefreshExpiration();
  }

  private void addVariableExpiration() {
    if (context.generateFeatures.contains(Feature.EXPIRE_ACCESS)) {
      addLink("previousInVariableOrder", "previousInAccessOrder");
      addLink("nextInVariableOrder", "nextInAccessOrder");
      addVariableTime("accessTime");
    } else if (context.generateFeatures.contains(Feature.EXPIRE_WRITE)) {
      addLink("previousInVariableOrder", "previousInWriteOrder");
      addLink("nextInVariableOrder", "nextInWriteOrder");
      addVariableTime("writeTime");
    }
    if (context.parentFeatures.contains(Feature.EXPIRE_ACCESS)
        && context.parentFeatures.contains(Feature.EXPIRE_WRITE)
        && context.generateFeatures.contains(Feature.REFRESH_WRITE)) {
      addLink("previousInVariableOrder", "previousInWriteOrder");
      addLink("nextInVariableOrder", "nextInWriteOrder");
      addVariableTime("accessTime");
    }
  }

  private void addLink(String method, String varName) {
    MethodSpec getter = MethodSpec.methodBuilder("get" + capitalize(method))
        .addModifiers(Modifier.PUBLIC)
        .addStatement("return $N", varName)
        .returns(NODE)
        .build();
    MethodSpec setter = MethodSpec.methodBuilder("set" + capitalize(method))
        .addModifiers(Modifier.PUBLIC)
        .addParameter(NODE, varName)
        .addStatement("this.$N = $N", varName, varName)
        .build();
    context.nodeSubtype
        .addMethod(getter)
        .addMethod(setter);
  }

  private void addVariableTime(String varName) {
    MethodSpec getter = MethodSpec.methodBuilder("getVariableTime")
        .addModifiers(Modifier.PUBLIC)
        .addStatement("return (long) $L.get(this)", varHandleName(varName))
        .returns(long.class)
        .build();
    MethodSpec setter = MethodSpec.methodBuilder("setVariableTime")
        .addModifiers(Modifier.PUBLIC)
        .addParameter(long.class, varName)
        .addStatement("$L.set(this, $N)", varHandleName(varName), varName)
        .build();
    MethodSpec cas = MethodSpec.methodBuilder("casVariableTime")
        .addModifiers(Modifier.PUBLIC)
        .addParameter(long.class, "expect")
        .addParameter(long.class, "update")
        .returns(boolean.class)
        .addStatement("return ($N == $N)\n&& $L.compareAndSet(this, $N, $N)",
            varName, "expect", varHandleName(varName), "expect", "update")
        .build();
    context.nodeSubtype
        .addMethod(getter)
        .addMethod(setter)
        .addMethod(cas);
  }

  private void addAccessExpiration() {
    if (!context.generateFeatures.contains(Feature.EXPIRE_ACCESS)) {
      return;
    }

    context.nodeSubtype
        .addField(long.class, "accessTime", Modifier.VOLATILE)
        .addMethod(newGetter(Strength.STRONG, TypeName.LONG, "accessTime", Visibility.PLAIN))
        .addMethod(newSetter(TypeName.LONG, "accessTime", Visibility.PLAIN));
    addVarHandle("accessTime", TypeName.get(long.class));
    addTimeConstructorAssignment(context.constructorByKey, "accessTime");
    addTimeConstructorAssignment(context.constructorByKeyRef, "accessTime");
  }

  private void addWriteExpiration() {
    if (!Feature.useWriteTime(context.parentFeatures)
        && Feature.useWriteTime(context.generateFeatures)) {
      context.nodeSubtype
          .addField(long.class, "writeTime", Modifier.VOLATILE)
          .addMethod(newGetter(Strength.STRONG, TypeName.LONG, "writeTime", Visibility.PLAIN))
          .addMethod(newSetter(TypeName.LONG, "writeTime", Visibility.PLAIN));
      addVarHandle("writeTime", TypeName.get(long.class));
      addTimeConstructorAssignment(context.constructorByKey, "writeTime");
      addTimeConstructorAssignment(context.constructorByKeyRef, "writeTime");
    }
  }

  private void addRefreshExpiration() {
    if (!context.generateFeatures.contains(Feature.REFRESH_WRITE)) {
      return;
    }
    context.nodeSubtype.addMethod(MethodSpec.methodBuilder("casWriteTime")
        .addModifiers(context.publicFinalModifiers())
        .addParameter(long.class, "expect")
        .addParameter(long.class, "update")
        .returns(boolean.class)
        .addStatement("return ($N == $N)\n&& $L.compareAndSet(this, $N, $N)",
            "writeTime", "expect", varHandleName("writeTime"), "expect", "update")
        .build());
  }

  /** Adds a long constructor assignment. */
  private void addTimeConstructorAssignment(MethodSpec.Builder constructor, String field) {
    constructor.addStatement("$L.set(this, $N)", varHandleName(field), "now");
  }
}
