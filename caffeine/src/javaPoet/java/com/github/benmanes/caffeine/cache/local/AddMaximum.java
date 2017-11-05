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
import static com.github.benmanes.caffeine.cache.Specifications.UNSAFE_ACCESS;
import static com.github.benmanes.caffeine.cache.Specifications.newFieldOffset;
import static com.github.benmanes.caffeine.cache.Specifications.offsetName;
import static org.apache.commons.lang3.StringUtils.capitalize;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddMaximum extends LocalCacheRule {

  @Override
  protected boolean applies() {
    return !(Feature.usesMaximum(context.parentFeatures)
        || !Feature.usesMaximum(context.generateFeatures));
  }

  @Override
  protected void execute() {
    addEvicts();
    addMaximum("");
    addMaximum("eden");
    addMaximum("mainProtected");
    addWeightedSize("");
    addWeightedSize("eden");
    addWeightedSize("mainProtected");
    addFrequencySketch();
  }

  private void addEvicts() {
    context.cache.addMethod(MethodSpec.methodBuilder("evicts")
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return true")
        .returns(boolean.class)
        .build());
  }

  private void addMaximum(String prefix) {
    String varName = prefix.isEmpty() ? "maximum" : prefix + "Maximum";
    context.cache.addField(FieldSpec.builder(long.class, varName, Modifier.VOLATILE).build());
    context.cache.addField(newFieldOffset(context.className, varName));
    context.cache.addMethod(MethodSpec.methodBuilder(varName)
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return $T.UNSAFE.getLong(this, $N)", UNSAFE_ACCESS, offsetName(varName))
        .returns(long.class)
        .build());
    context.cache.addMethod(MethodSpec.methodBuilder("lazySet" + capitalize(varName))
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("$T.UNSAFE.putLong(this, $N, $N)",
            UNSAFE_ACCESS, offsetName(varName), varName)
        .addParameter(long.class, varName)
        .build());
  }

  private void addWeightedSize(String prefix) {
    String varName = prefix.isEmpty() ? "weightedSize" : prefix + "WeightedSize";
    context.cache.addField(FieldSpec.builder(long.class, varName, Modifier.VOLATILE).build());
    context.cache.addField(newFieldOffset(context.className, varName));
    context.cache.addMethod(MethodSpec.methodBuilder(varName)
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return $T.UNSAFE.getLong(this, $N)", UNSAFE_ACCESS, offsetName(varName))
        .returns(long.class)
        .build());
    context.cache.addMethod(MethodSpec.methodBuilder("lazySet" + capitalize(varName))
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("$T.UNSAFE.putLong(this, $N, $N)",
            UNSAFE_ACCESS, offsetName(varName), varName)
        .addParameter(long.class, varName)
        .build());
  }

  private void addFrequencySketch() {
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
}
