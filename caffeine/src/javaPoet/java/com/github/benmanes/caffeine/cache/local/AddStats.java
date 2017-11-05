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

import static com.github.benmanes.caffeine.cache.Specifications.STATS_COUNTER;
import static com.github.benmanes.caffeine.cache.Specifications.TICKER;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddStats extends LocalCacheRule {

  @Override
  protected boolean applies() {
    return context.generateFeatures.contains(Feature.STATS);
  }

  @Override
  protected void execute() {
    addIsRecording();
    addStatsTicker();
    addStatsCounter();
  }

  private void addIsRecording() {
    context.cache.addMethod(MethodSpec.methodBuilder("isRecordingStats")
        .addModifiers(context.publicFinalModifiers())
        .addStatement("return true")
        .returns(boolean.class)
        .build());
  }

  private void addStatsCounter() {
    context.constructor.addStatement("this.statsCounter = builder.getStatsCounterSupplier().get()");
    context.cache.addField(FieldSpec.builder(
        STATS_COUNTER, "statsCounter", Modifier.FINAL).build());
    context.cache.addMethod(MethodSpec.methodBuilder("statsCounter")
        .addModifiers(context.publicFinalModifiers())
        .addStatement("return statsCounter")
        .returns(STATS_COUNTER)
        .build());
  }

  private void addStatsTicker() {
    context.cache.addMethod(MethodSpec.methodBuilder("statsTicker")
        .addModifiers(context.publicFinalModifiers())
        .addStatement("return $T.systemTicker()", TICKER)
        .returns(TICKER)
        .build());
  }
}
