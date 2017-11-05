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
package com.github.benmanes.caffeine.cache.local;

import static com.github.benmanes.caffeine.cache.Specifications.kRefQueueType;
import static com.github.benmanes.caffeine.cache.Specifications.vRefQueueType;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddKeyValueStrength extends LocalCacheRule {

  @Override
  protected boolean applies() {
    return true;
  }

  @Override
  protected void execute() {
    addKeyStrength();
    addValueStrength();
  }

  private void addKeyStrength() {
    if (context.generateFeatures.contains(Feature.WEAK_KEYS)) {
      addStrength("collectKeys", "keyReferenceQueue", kRefQueueType);
    }
  }

  private void addValueStrength() {
    if (context.generateFeatures.contains(Feature.INFIRM_VALUES)) {
      addStrength("collectValues", "valueReferenceQueue", vRefQueueType);
    }
  }

  /** Adds the reference strength methods for the key or value. */
  private void addStrength(String collectName, String queueName, TypeName type) {
    context.cache.addMethod(MethodSpec.methodBuilder(queueName)
        .addModifiers(context.protectedFinalModifiers())
        .returns(type)
        .addStatement("return $N", queueName)
        .build());
    context.cache.addField(FieldSpec.builder(type, queueName, Modifier.FINAL)
        .initializer("new $T()", type)
        .build());
    context.cache.addMethod(MethodSpec.methodBuilder(collectName)
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return true")
        .returns(boolean.class)
        .build());
  }
}
