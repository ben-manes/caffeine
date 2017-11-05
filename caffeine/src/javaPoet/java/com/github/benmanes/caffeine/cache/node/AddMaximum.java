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

import com.github.benmanes.caffeine.cache.Feature;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;

/**
 * Adds the maximum metadata to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AddMaximum extends NodeRule {

  @Override
  protected boolean applies() {
    return Feature.usesMaximum(context.generateFeatures);
  }

  @Override
  protected void execute() {
    addQueueFlag();
    addWeight();
  }

  private void addQueueFlag() {
    context.nodeSubtype.addField(int.class, "queueType");
    context.nodeSubtype.addMethod(MethodSpec.methodBuilder("getQueueType")
        .addModifiers(context.publicFinalModifiers())
        .returns(int.class)
        .addStatement("return queueType")
        .build());
    context.nodeSubtype.addMethod(MethodSpec.methodBuilder("setQueueType")
        .addModifiers(context.publicFinalModifiers())
        .addParameter(int.class, "queueType")
        .addStatement("this.queueType = queueType")
        .build());
  }

  private void addWeight() {
    if (!context.generateFeatures.contains(Feature.MAXIMUM_WEIGHT)) {
      return;
    }
    context.nodeSubtype.addField(int.class, "weight")
        .addMethod(newGetter(Strength.STRONG, TypeName.INT, "weight", Visibility.IMMEDIATE))
        .addMethod(newSetter(TypeName.INT, "weight", Visibility.IMMEDIATE));
    context.constructorByKey.addStatement("this.$N = $N", "weight", "weight");
    context.constructorByKeyRef.addStatement("this.$N = $N", "weight", "weight");

    context.nodeSubtype.addField(int.class, "policyWeight")
        .addMethod(newGetter(Strength.STRONG, TypeName.INT, "policyWeight", Visibility.IMMEDIATE))
        .addMethod(newSetter(TypeName.INT, "policyWeight", Visibility.IMMEDIATE));
  }
}
