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

import com.github.benmanes.caffeine.cache.Feature;
import com.github.benmanes.caffeine.cache.node.NodeContext.Strength;
import com.github.benmanes.caffeine.cache.node.NodeContext.Visibility;

/**
 * Adds the access and write deques, if needed, to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddDeques implements NodeRule {

  @Override
  public boolean applies(NodeContext context) {
    return true;
  }

  @Override
  public void execute(NodeContext context) {
    if (!Feature.usesAccessOrderWindowDeque(context.parentFeatures)
        && Feature.usesAccessOrderWindowDeque(context.generateFeatures)) {
      addFieldAndGetter(context, "previousInAccessOrder");
      addFieldAndGetter(context, "nextInAccessOrder");
    }
    if (!Feature.usesWriteOrderDeque(context.parentFeatures)
        && Feature.usesWriteOrderDeque(context.generateFeatures)) {
      addFieldAndGetter(context, "previousInWriteOrder");
      addFieldAndGetter(context, "nextInWriteOrder");
    }
  }

  /** Adds a simple field, accessor, and mutator for the variable. */
  private static void addFieldAndGetter(NodeContext context, String varName) {
    context.nodeSubtype.addField(NODE, varName)
        .addMethod(context.newGetter(Strength.STRONG, NODE, varName, Visibility.VOLATILE))
        .addMethod(context.newSetter(NODE, varName, Visibility.VOLATILE));
  }
}
