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
public final class AddConstructors extends NodeRule {

  @Override
  protected boolean applies() {
    return true;
  }

  @Override
  protected void execute() {
    makeBaseConstructorByKey();
    makeBaseConstructorByKeyRef();
  }

  /** Adds the constructor by key to the node type. */
  private void makeBaseConstructorByKey() {
    context.constructorByKey = MethodSpec.constructorBuilder().addParameter(keySpec);
    context.constructorByKey.addParameter(keyRefQueueSpec);
    completeBaseConstructor(context.constructorByKey);
    if (!isBaseClass()) {
      context.constructorByKey.addStatement(
          "super(key, keyReferenceQueue, value, valueReferenceQueue, weight, now)");
    }
  }

  /** Adds the constructor by key reference to the node type. */
  private void makeBaseConstructorByKeyRef() {
    context.constructorByKeyRef = MethodSpec.constructorBuilder().addParameter(keyRefSpec);
    completeBaseConstructor(context.constructorByKeyRef);
    if (!isBaseClass()) {
      context.constructorByKeyRef.addStatement(
          "super(keyReference, value, valueReferenceQueue, weight, now)");
    }
  }

  private void completeBaseConstructor(MethodSpec.Builder constructor) {
    constructor.addParameter(valueSpec);
    constructor.addParameter(valueRefQueueSpec);
    constructor.addParameter(int.class, "weight");
    constructor.addParameter(long.class, "now");
  }
}
