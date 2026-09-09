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
import com.github.benmanes.caffeine.cache.Rule;
import com.github.benmanes.caffeine.cache.node.NodeContext.Strength;
import com.github.benmanes.caffeine.cache.node.NodeContext.FieldAccess;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;

/**
 * Adds the maximum metadata to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddMaximum implements Rule<NodeContext> {

  @Override
  public boolean applies(NodeContext context) {
    return Feature.usesMaximum(context.generateFeatures);
  }

  @Override
  public void execute(NodeContext context) {
    addMetadata(context);
    addWeight(context);
  }

  private static void addMetadata(NodeContext context) {
    boolean weighted = context.generateFeatures.contains(Feature.MAXIMUM_WEIGHT);
    context.classSpec.addField(int.class, "metadata");
    context.classSpec.addMethod(MethodSpec.methodBuilder("getQueueType")
        .addModifiers(context.publicFinalModifiers())
        .returns(int.class)
        .addStatement(weighted ? "return metadata & QUEUE_MASK" : "return metadata")
        .build());
    context.classSpec.addMethod(MethodSpec.methodBuilder("setQueueType")
        .addModifiers(context.publicFinalModifiers())
        .addParameter(int.class, "queueType")
        .addStatement(weighted
            ? "this.metadata = (this.metadata & ~QUEUE_MASK) | queueType"
            : "this.metadata = queueType")
        .build());
  }

  private static void addWeight(NodeContext context) {
    if (!context.generateFeatures.contains(Feature.MAXIMUM_WEIGHT)) {
      return;
    }
    context.classSpec.addField(int.class, "weight")
        .addMethod(context.newGetter(Strength.STRONG,
            TypeName.INT, "weight", FieldAccess.DIRECT))
        .addMethod(context.newSetter(TypeName.INT, "weight", FieldAccess.DIRECT));
    context.constructorByKeyRef.addStatement("this.$N = $N", "weight", "weight");

    // Reordered update tasks can drive the policy weight past the int range before the replay
    // settles, and a transfer that copied a truncated value into the long region totals would
    // leave a residue that no later task repairs. Its low half lives in its own field and its
    // high half in the metadata bits the queue type does not use, so no node grows.
    context.classSpec.addField(int.class, "policyWeight");
    context.classSpec.addMethod(MethodSpec.methodBuilder("getPolicyWeight")
        .addModifiers(context.publicFinalModifiers())
        .returns(long.class)
        .addStatement("return ((long) (metadata >> QUEUE_BITS) << Integer.SIZE)"
            + " | Integer.toUnsignedLong(policyWeight)")
        .build());
    context.classSpec.addMethod(MethodSpec.methodBuilder("setPolicyWeight")
        .addModifiers(context.publicFinalModifiers())
        .addParameter(long.class, "policyWeight")
        .addStatement("this.policyWeight = (int) policyWeight")
        .addStatement("this.metadata = (this.metadata & QUEUE_MASK)"
            + " | ((int) (policyWeight >> Integer.SIZE) << QUEUE_BITS)")
        .build());
  }
}
