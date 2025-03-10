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

import static com.github.benmanes.caffeine.cache.Specifications.FREQUENCY_SKETCH;
import static org.apache.commons.lang3.StringUtils.capitalize;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddMaximum implements LocalCacheRule {

  @Override
  public boolean applies(LocalCacheContext context) {
    return !(Feature.usesMaximum(context.parentFeatures)
        || !Feature.usesMaximum(context.generateFeatures));
  }

  @Override
  public void execute(LocalCacheContext context) {
    addEvicts(context);
    addMaximumSize(context);
    addHillClimber(context);
    addFrequencySketch(context);
  }

  private static void addEvicts(LocalCacheContext context) {
    context.cache.addMethod(MethodSpec.methodBuilder("evicts")
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return true")
        .returns(boolean.class)
        .build());
  }

  private static void addMaximumSize(LocalCacheContext context) {
    addField(context, long.class, "maximum");
    addField(context, long.class, "weightedSize");
    addField(context, long.class, "windowMaximum");
    addField(context, long.class, "windowWeightedSize");
    addField(context, long.class, "mainProtectedMaximum");
    addField(context, long.class, "mainProtectedWeightedSize");
  }

  private static void addHillClimber(LocalCacheContext context) {
    addField(context, double.class, "stepSize");
    addField(context, long.class, "adjustment");
    addField(context, int.class, "hitsInSample");
    addField(context, int.class, "missesInSample");
    addField(context, double.class, "previousSampleHitRate");
  }

  private static void addFrequencySketch(LocalCacheContext context) {
    context.cache.addField(FieldSpec.builder(
        FREQUENCY_SKETCH, "sketch", Modifier.FINAL).build());
    context.constructor.addCode(CodeBlock.builder()
        .addStatement("this.sketch = new $T()", FREQUENCY_SKETCH)
        .beginControlFlow("if (builder.hasInitialCapacity())")
            .addStatement("long capacity = Math.min($L, $L)",
                "builder.getMaximum()", "builder.getInitialCapacity()")
            .addStatement("this.sketch.ensureCapacity(capacity)")
        .endControlFlow().build());
    context.cache.addMethod(MethodSpec.methodBuilder("frequencySketch")
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return sketch")
        .returns(FREQUENCY_SKETCH)
        .build());
  }

  private static void addField(LocalCacheContext context, Class<?> type, String name) {
    context.cache.addField(FieldSpec.builder(type, name).build());
    context.cache.addMethod(MethodSpec.methodBuilder(name)
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return $L", name)
        .returns(type)
        .build());
    context.cache.addMethod(MethodSpec.methodBuilder("set" + capitalize(name))
        .addModifiers(context.protectedFinalModifiers())
        .addParameter(type, name)
        .addStatement("this.$1L = $1L", name)
        .build());
  }
}
