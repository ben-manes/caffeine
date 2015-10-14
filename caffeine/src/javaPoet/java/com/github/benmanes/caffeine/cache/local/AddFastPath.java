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
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddFastPath extends LocalCacheRule {

  @Override
  protected boolean applies() {
    return context.generateFeatures.contains(Feature.FASTPATH);
  }

  @Override
  protected void execute() {
    addMoveDistance();
    addMoveCount();
    addFlag();
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

  private void addMoveCount() {
    context.cache.addField(FieldSpec.builder(
        int.class, "moveCount", Modifier.PRIVATE).build());
    context.cache.addMethod(MethodSpec.methodBuilder("moveCount")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return moveCount")
        .returns(int.class)
        .build());
    context.cache.addMethod(MethodSpec.methodBuilder("setMoveCount")
        .addModifiers(protectedFinalModifiers)
        .addParameter(int.class, "moveCount")
        .addStatement("this.moveCount = moveCount")
        .build());
  }

  private void addFlag() {
    context.cache.addMethod(MethodSpec.methodBuilder("fastpath")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return true")
        .returns(boolean.class)
        .build());
  }
}
