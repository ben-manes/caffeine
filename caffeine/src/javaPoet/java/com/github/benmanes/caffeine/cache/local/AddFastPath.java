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

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.google.common.collect.Sets;
import com.squareup.javapoet.MethodSpec;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddFastPath extends LocalCacheRule {

  @Override
  protected boolean applies() {
    boolean parentFastPath = Feature.usesFastPath(context.parentFeatures);
    boolean fastpath = Feature.usesFastPath(Sets.union(
        context.parentFeatures, context.generateFeatures));
    return (parentFastPath != fastpath);
  }

  @Override
  protected void execute() {
    boolean fastpath = Feature.usesFastPath(Sets.union(
        context.parentFeatures, context.generateFeatures));
    context.cache.addMethod(MethodSpec.methodBuilder("fastpath")
        .addStatement("return " + Boolean.toString(fastpath))
        .addModifiers(Modifier.PROTECTED)
        .returns(boolean.class)
        .build());
  }
}
