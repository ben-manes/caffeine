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
import com.github.benmanes.caffeine.cache.node.NodeContext.Strength;
import com.github.benmanes.caffeine.cache.node.NodeContext.Visibility;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;

/**
 * Adds the maximum metadata to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddMaximum implements NodeRule {

  @Override
  public boolean applies(NodeContext context) {
    return Feature.usesMaximum(context.generateFeatures);
  }

  @Override
  public void execute(NodeContext context) {
    addQueueFlag(context);
    addWeight(context);
  }

  private static void addQueueFlag(NodeContext context) {
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

  private static void addWeight(NodeContext context) {
    if (!context.generateFeatures.contains(Feature.MAXIMUM_WEIGHT)) {
      return;
    }
    context.nodeSubtype.addField(int.class, "weight")
        .addMethod(context.newGetter(Strength.STRONG,
            TypeName.INT, "weight", Visibility.VOLATILE))
        .addMethod(context.newSetter(TypeName.INT, "weight", Visibility.VOLATILE));
    context.constructorByKey.addStatement("this.$N = $N", "weight", "weight");
    context.constructorByKeyRef.addStatement("this.$N = $N", "weight", "weight");

    context.nodeSubtype.addField(int.class, "policyWeight")
        .addMethod(context.newGetter(Strength.STRONG,
            TypeName.INT, "policyWeight", Visibility.VOLATILE))
        .addMethod(context.newSetter(TypeName.INT, "policyWeight", Visibility.VOLATILE));
  }
}
