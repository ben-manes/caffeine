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
import static com.github.benmanes.caffeine.cache.Specifications.keyRefSpec;
import static com.github.benmanes.caffeine.cache.Specifications.keySpec;
import static com.github.benmanes.caffeine.cache.Specifications.valueRefQueueSpec;
import static com.github.benmanes.caffeine.cache.Specifications.valueSpec;

import com.squareup.javapoet.MethodSpec;

/**
 * Adds the constructors to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AddConstructors extends NodeRule {

  @Override
  protected boolean applies() {
    return true;
  }

  @Override
  protected void execute() {
    addConstructorDefault();
    addConstructorByKey();
    addConstructorByKeyRef();
  }

  /** Adds the constructor used to create a factory. */
  private void addConstructorDefault() {
    context.constructorDefault = MethodSpec.constructorBuilder();
  }

  /** Adds the constructor by key to the node type. */
  private void addConstructorByKey() {
    context.constructorByKey = MethodSpec.constructorBuilder().addParameter(keySpec);
    context.constructorByKey.addParameter(keyRefQueueSpec);
    addCommonParameters(context.constructorByKey);
    if (isBaseClass()) {
      callSiblingConstructor();
    } else {
      callParentByKey();
    }
  }

  /** Adds the constructor by key reference to the node type. */
  private void addConstructorByKeyRef() {
    context.constructorByKeyRef = MethodSpec.constructorBuilder().addParameter(keyRefSpec);
    addCommonParameters(context.constructorByKeyRef);
    if (isBaseClass()) {
      assignKeyRefAndValue();
    } else {
      callParentByKeyRef();
    }
  }

  private void addCommonParameters(MethodSpec.Builder constructor) {
    constructor.addParameter(valueSpec);
    constructor.addParameter(valueRefQueueSpec);
    constructor.addParameter(int.class, "weight");
    constructor.addParameter(long.class, "now");
  }

  private void callSiblingConstructor() {
    if (isStrongKeys()) {
      context.constructorByKey.addStatement("this(key, value, valueReferenceQueue, weight, now)");
    } else {
      context.constructorByKey.addStatement(
          "this(new $T($N, $N), value, valueReferenceQueue, weight, now)", keyReferenceType(),
          "key", "keyReferenceQueue");
    }
  }

  private void assignKeyRefAndValue() {
    context.constructorByKeyRef.addStatement("$L.set(this, $N)",
        varHandleName("key"), "keyReference");
    if (isStrongValues()) {
      context.constructorByKeyRef.addStatement("$L.set(this, $N)",
          varHandleName("value"), "value");
    } else {
      context.constructorByKeyRef.addStatement("$L.set(this, new $T($N, $N, $N))",
          varHandleName("value"), valueReferenceType(), "keyReference",
          "value", "valueReferenceQueue");
    }
  }

  private void callParentByKey() {
    context.constructorByKey.addStatement(
        "super(key, keyReferenceQueue, value, valueReferenceQueue, weight, now)");
  }

  private void callParentByKeyRef() {
    context.constructorByKeyRef.addStatement(
        "super(keyReference, value, valueReferenceQueue, weight, now)");
  }
}
