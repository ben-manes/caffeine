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

import java.util.concurrent.Executor;

import com.github.benmanes.caffeine.cache.Feature;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddExecutor extends LocalCacheRule {

  @Override
  protected boolean applies() {
    return context.generateFeatures.contains(Feature.EXECUTOR);
  }

  @Override
  protected void execute() {
    context.cache.addField(FieldSpec.builder(
        Executor.class, "executor", privateFinalModifiers).build());
    context.constructor.addStatement("this.executor = builder.getExecutor()");
    context.cache.addMethod(MethodSpec.methodBuilder("executor")
        .addModifiers(publicFinalModifiers)
        .addStatement("return executor")
        .returns(Executor.class)
        .build());
  }
}
