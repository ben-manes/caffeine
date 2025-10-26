/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
 * Refactorizado para cumplir con el Principio de Inversión de Dependencias (DIP).
 * Ahora depende de la interfaz INodeContext en lugar de la clase concreta NodeContext.
 *
 * @author ben.manes
 */
public final class AddConstructors implements NodeRule {

  @Override
  public boolean applies(INodeContext context) {  // CAMBIO 1: se usa la interfaz
    return true;
  }

  @Override
  public void execute(INodeContext context) {     // CAMBIO 2: se usa la interfaz
    addConstructorByKey(context);
    addConstructorByKeyRef(context);
    if (context.isBaseClass()) {
      // CAMBIO 3: sin acceso directo a campos internos, uso de método de interfaz
      context.getConstructorByKey().addComment("Warnings suppressed for base class");
    }
  }

  /** Adds the constructor by key to the node type. */
  private static void addConstructorByKey(INodeContext context) { // CAMBIO 4
    MethodSpec.Builder constructor = context.getConstructorByKey();
    constructor.addParameter(keyRefQueueSpec);
    addCommonParameters(constructor);
    if (context.isBaseClass()) {
      callSiblingConstructor(context);
    } else {
      callParentByKey(context);
    }
  }

  /** Adds the constructor by key reference to the node type. */
  private static void addConstructorByKeyRef(INodeContext context) { // CAMBIO 5
    MethodSpec.Builder constructor = context.getConstructorByKeyRef();
    addCommonParameters(constructor);
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

  private static void callSiblingConstructor(INodeContext context) { // CAMBIO 6
    MethodSpec.Builder constructor = context.getConstructorByKey();
    if (context.isStrongKeys()) {
      constructor.addStatement("this(key, value, valueReferenceQueue, weight, now)");
    } else {
      constructor.addStatement(
          "this(new $T($N, $N), value, valueReferenceQueue, weight, now)",
          NodeContext.class, "key", "keyReferenceQueue");
    }
  }

  private static void assignKeyRefAndValue(INodeContext context) { // CAMBIO 7
    MethodSpec.Builder constructor = context.getConstructorByKeyRef();
    if (context.isStrongValues()) {
      constructor.addStatement("$L.set(this, $N)", varHandleName("key"), "keyReference");
      constructor.addStatement("$L.set(this, $N)", varHandleName("value"), "value");
    } else {
      constructor.addStatement("$L.set(this, new $T($N, $N, $N))",
          varHandleName("value"), NodeContext.class,
          "keyReference", "value", "valueReferenceQueue");
    }
  }

  private static void callParentByKey(INodeContext context) { // CAMBIO 8
    context.getConstructorByKey().addStatement(
        "super(key, keyReferenceQueue, value, valueReferenceQueue, weight, now)");
  }

  private static void callParentByKeyRef(INodeContext context) { // CAMBIO 9
    context.getConstructorByKeyRef().addStatement(
        "super(keyReference, value, valueReferenceQueue, weight, now)");
  }
}

