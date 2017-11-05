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

import static com.github.benmanes.caffeine.cache.Specifications.WRITE_QUEUE;
import static com.github.benmanes.caffeine.cache.Specifications.WRITE_QUEUE_TYPE;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddWriteBuffer extends LocalCacheRule {

  @Override
  protected boolean applies() {
    return !(Feature.usesWriteQueue(context.parentFeatures)
        || !Feature.usesWriteQueue(context.generateFeatures));
  }

  @Override
  protected void execute() {
    context.constructor.addStatement(
        "this.writeBuffer = new $T<>(WRITE_BUFFER_MIN, WRITE_BUFFER_MAX)", WRITE_QUEUE_TYPE);
    context.cache.addField(FieldSpec.builder(
        WRITE_QUEUE, "writeBuffer", Modifier.FINAL).build());
    context.cache.addMethod(MethodSpec.methodBuilder("writeBuffer")
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return writeBuffer")
        .returns(WRITE_QUEUE)
        .build());
    context.cache.addMethod(MethodSpec.methodBuilder("buffersWrites")
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return true")
        .returns(boolean.class)
        .build());
  }
}
