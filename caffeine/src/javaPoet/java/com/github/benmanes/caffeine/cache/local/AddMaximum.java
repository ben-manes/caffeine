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

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddMaximum extends LocalCacheRule {

  @Override
  protected boolean applies() {
    if (Feature.usesMaximum(context.parentFeatures)
        || !Feature.usesMaximum(context.generateFeatures)) {
      return false;
    }
    return true;
  }

  @Override
  protected void execute() {
    addEvicts();
    addMaximum();
    addWeightedSize();
    addFrequencySketch();
    addMoveDistance();
    addMoveCounter();
  }

  private void addEvicts() {
    context.cache.addMethod(MethodSpec.methodBuilder("evicts")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return true")
        .returns(boolean.class)
        .build());
  }

  private void addMaximum() {
    context.constructor.addStatement(
        "this.maximum = $T.min(builder.getMaximumWeight(), MAXIMUM_CAPACITY)", Math.class);
    context.cache.addField(FieldSpec.builder(long.class, "maximum", privateVolatileModifiers).build());
    context.cache.addField(newFieldOffset(context.className, "maximum"));
    context.cache.addMethod(MethodSpec.methodBuilder("maximum")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return $T.UNSAFE.getLong(this, $N)", UNSAFE_ACCESS, offsetName("maximum"))
        .returns(long.class)
        .build());
    context.cache.addMethod(MethodSpec.methodBuilder("lazySetMaximum")
        .addModifiers(protectedFinalModifiers)
        .addStatement("$T.UNSAFE.putLong(this, $N, $N)",
            UNSAFE_ACCESS, offsetName("maximum"), "maximum")
        .addParameter(long.class, "maximum")
        .build());
  }

  private void addWeightedSize() {
    context.cache.addField(FieldSpec.builder(
        long.class, "weightedSize", privateVolatileModifiers).build());
    context.cache.addField(newFieldOffset(context.className, "weightedSize"));
    context.cache.addMethod(MethodSpec.methodBuilder("weightedSize")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return $T.UNSAFE.getLong(this, $N)",
            UNSAFE_ACCESS, offsetName("weightedSize"))
        .returns(long.class)
        .build());
    context.cache.addMethod(MethodSpec.methodBuilder("lazySetWeightedSize")
        .addModifiers(protectedFinalModifiers)
        .addStatement("$T.UNSAFE.putLong(this, $N, $N)",
            UNSAFE_ACCESS, offsetName("weightedSize"), "weightedSize")
        .addParameter(long.class, "weightedSize")
        .build());
  }

  private void addFrequencySketch() {
    context.cache.addField(FieldSpec.builder(
        FREQUENCY_SKETCH, "sketch", privateFinalModifiers).build());
    context.constructor.addStatement("this.sketch = new $T(0)", FREQUENCY_SKETCH);
    context.cache.addMethod(MethodSpec.methodBuilder("frequencySketch")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return sketch")
        .returns(FREQUENCY_SKETCH)
        .build());
  }

  private void addMoveDistance() {
    context.cache.addField(FieldSpec.builder(
        int.class, "moveDistance", Modifier.PRIVATE).build());
    context.cache.addMethod(MethodSpec.methodBuilder("moveDistance")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return moveDistance")
        .returns(int.class)
        .build());
    context.cache.addMethod(MethodSpec.methodBuilder("setMoveDistance")
        .addModifiers(protectedFinalModifiers)
        .addParameter(int.class, "moveDistance")
        .addStatement("this.moveDistance = moveDistance")
        .build());
  }

  private void addMoveCounter() {
    context.cache.addField(FieldSpec.builder(
        int.class, "moveCounter", Modifier.PRIVATE).build());
    context.cache.addMethod(MethodSpec.methodBuilder("moveCounter")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return moveCounter")
        .returns(int.class)
        .build());
    context.cache.addMethod(MethodSpec.methodBuilder("setMoveCounter")
        .addModifiers(protectedFinalModifiers)
        .addParameter(int.class, "moveCounter")
        .addStatement("this.moveCounter = moveCounter")
        .build());
  }
}
