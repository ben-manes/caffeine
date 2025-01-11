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

import static com.github.benmanes.caffeine.cache.Specifications.keyRefQueueSpec;
import static com.github.benmanes.caffeine.cache.Specifications.valueRefQueueSpec;
import static com.github.benmanes.caffeine.cache.Specifications.valueSpec;
import static com.github.benmanes.caffeine.cache.node.NodeContext.varHandleName;

import com.palantir.javapoet.MethodSpec;

/**
 * Adds the constructors to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddConstructors implements NodeRule {

  @Override
  public boolean applies(NodeContext context) {
    return true;
  }

  @Override
  public void execute(NodeContext context) {
    addConstructorByKey(context);
    addConstructorByKeyRef(context);
    if (context.isBaseClass()) {
      context.suppressedWarnings.add("unchecked");
      context.suppressedWarnings.add("PMD.UnusedFormalParameter");
    }
  }

  /** Adds the constructor by key to the node type. */
  private static void addConstructorByKey(NodeContext context) {
    context.constructorByKey.addParameter(keyRefQueueSpec);
    addCommonParameters(context.constructorByKey);
    if (context.isBaseClass()) {
      callSiblingConstructor(context);
    } else {
      callParentByKey(context);
    }
  }

  /** Adds the constructor by key reference to the node type. */
  private static void addConstructorByKeyRef(NodeContext context) {
    addCommonParameters(context.constructorByKeyRef);
    if (context.isBaseClass()) {
      assignKeyRefAndValue(context);
    } else {
      callParentByKeyRef(context);
    }
  }

  private static void addCommonParameters(MethodSpec.Builder constructor) {
    constructor.addParameter(valueSpec);
    constructor.addParameter(valueRefQueueSpec);
    constructor.addParameter(int.class, "weight");
    constructor.addParameter(long.class, "now");
  }

  private static void callSiblingConstructor(NodeContext context) {
    if (context.isStrongKeys()) {
      context.constructorByKey.addStatement("this(key, value, valueReferenceQueue, weight, now)");
    } else {
      context.constructorByKey.addStatement(
          "this(new $T($N, $N), value, valueReferenceQueue, weight, now)",
          context.keyReferenceType(), "key", "keyReferenceQueue");
    }
  }

  private static void assignKeyRefAndValue(NodeContext context) {
    if (context.isStrongValues()) {
      context.constructorByKeyRef.addStatement("$L.set(this, $N)",
          varHandleName("key"), "keyReference");
      context.constructorByKeyRef.addStatement("$L.set(this, $N)",
          varHandleName("value"), "value");
    } else {
      context.constructorByKeyRef.addStatement("$L.set(this, new $T($N, $N, $N))",
          varHandleName("value"), context.valueReferenceType(),
          "keyReference", "value", "valueReferenceQueue");
    }
  }

  private static void callParentByKey(NodeContext context) {
    context.constructorByKey.addStatement(
        "super(key, keyReferenceQueue, value, valueReferenceQueue, weight, now)");
  }

  private static void callParentByKeyRef(NodeContext context) {
    context.constructorByKeyRef.addStatement(
        "super(keyReference, value, valueReferenceQueue, weight, now)");
  }
}
