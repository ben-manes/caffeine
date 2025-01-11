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
import static com.github.benmanes.caffeine.cache.node.NodeContext.varHandleName;
import static org.apache.commons.lang3.StringUtils.capitalize;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.github.benmanes.caffeine.cache.node.NodeContext.Strength;
import com.github.benmanes.caffeine.cache.node.NodeContext.Visibility;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;

/**
 * Adds the expiration support to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddExpiration implements NodeRule {

  @Override
  public boolean applies(NodeContext context) {
    return true;
  }

  @Override
  public void execute(NodeContext context) {
    addVariableExpiration(context);
    addAccessExpiration(context);
    addWriteExpiration(context);
    addRefreshExpiration(context);
  }

  private static void addVariableExpiration(NodeContext context) {
    if (context.generateFeatures.contains(Feature.EXPIRE_ACCESS)) {
      addLink(context, "previousInVariableOrder", "previousInAccessOrder");
      addLink(context, "nextInVariableOrder", "nextInAccessOrder");
      addVariableTime(context, "accessTime");
    } else if (context.generateFeatures.contains(Feature.EXPIRE_WRITE)) {
      addLink(context, "previousInVariableOrder", "previousInWriteOrder");
      addLink(context, "nextInVariableOrder", "nextInWriteOrder");
      addVariableTime(context, "writeTime");
    }
    if (context.parentFeatures.contains(Feature.EXPIRE_ACCESS)
        && context.parentFeatures.contains(Feature.EXPIRE_WRITE)
        && context.generateFeatures.contains(Feature.REFRESH_WRITE)) {
      addLink(context, "previousInVariableOrder", "previousInWriteOrder");
      addLink(context, "nextInVariableOrder", "nextInWriteOrder");
      addVariableTime(context, "accessTime");
    }
  }

  private static void addLink(NodeContext context, String method, String varName) {
    var getter = MethodSpec.methodBuilder("get" + capitalize(method))
        .addModifiers(Modifier.PUBLIC)
        .addStatement("return $N", varName)
        .returns(NODE)
        .build();
    var setter = MethodSpec.methodBuilder("set" + capitalize(method))
        .addModifiers(Modifier.PUBLIC)
        .addParameter(NODE, varName)
        .addStatement("this.$N = $N", varName, varName)
        .build();
    context.nodeSubtype
        .addMethod(getter)
        .addMethod(setter);
  }

  private static void addVariableTime(NodeContext context, String varName) {
    var getter = MethodSpec.methodBuilder("getVariableTime")
        .addModifiers(Modifier.PUBLIC)
        .addStatement("return (long) $L.getOpaque(this)", varHandleName(varName))
        .returns(long.class)
        .build();
    var setter = MethodSpec.methodBuilder("setVariableTime")
        .addModifiers(Modifier.PUBLIC)
        .addParameter(long.class, varName)
        .addStatement("$L.setOpaque(this, $N)", varHandleName(varName), varName)
        .build();
    var cas = MethodSpec.methodBuilder("casVariableTime")
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

  private static void addAccessExpiration(NodeContext context) {
    if (!context.generateFeatures.contains(Feature.EXPIRE_ACCESS)) {
      return;
    }

    context.nodeSubtype
        .addField(long.class, "accessTime", Modifier.VOLATILE)
        .addMethod(context.newGetter(Strength.STRONG,
            TypeName.LONG, "accessTime", Visibility.OPAQUE))
        .addMethod(context.newSetter(TypeName.LONG, "accessTime", Visibility.OPAQUE));
    context.addVarHandle("accessTime", TypeName.get(long.class));
    addTimeConstructorAssignment(context.constructorByKey, "accessTime", "now");
    addTimeConstructorAssignment(context.constructorByKeyRef, "accessTime", "now");
  }

  private static void addWriteExpiration(NodeContext context) {
    if (!Feature.useWriteTime(context.parentFeatures)
        && Feature.useWriteTime(context.generateFeatures)) {
      context.nodeSubtype
          .addField(long.class, "writeTime", Modifier.VOLATILE)
          .addMethod(context.newGetter(Strength.STRONG,
              TypeName.LONG, "writeTime", Visibility.OPAQUE))
          .addMethod(context.newSetter(TypeName.LONG, "writeTime", Visibility.PLAIN));
      context.addVarHandle("writeTime", TypeName.get(long.class));
      addTimeConstructorAssignment(context.constructorByKey, "writeTime", "now & ~1L");
      addTimeConstructorAssignment(context.constructorByKeyRef, "writeTime", "now & ~1L");
    }
  }

  private static void addRefreshExpiration(NodeContext context) {
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
  private static void addTimeConstructorAssignment(
      MethodSpec.Builder constructor, String field, String value) {
    constructor.addStatement("$L.set(this, $N)", varHandleName(field), value);
  }
}
