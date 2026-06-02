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

import com.github.benmanes.caffeine.cache.Rule;

/**
 * Finishes construction of the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Finalize implements Rule<NodeContext> {

  @Override
  public boolean applies(NodeContext context) {
    return true;
  }

  @Override
  public void execute(NodeContext context) {
    context.classSpec
        .addMethod(context.constructorDefault.build())
        .addMethod(context.constructorByKey.build())
        .addMethod(context.constructorByKeyRef.build());
    context.addSuppressedWarnings();
    context.addStaticBlock();
  }
}
