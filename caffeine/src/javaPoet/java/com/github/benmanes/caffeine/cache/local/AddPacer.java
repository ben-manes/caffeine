/*
 * Copyright 2019 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.cache.Specifications.PACER;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.github.benmanes.caffeine.cache.Rule;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddPacer implements Rule<LocalCacheContext> {

  @Override
  public boolean applies(LocalCacheContext context) {
    return !(Feature.usesExpiration(context.parentFeatures)
        || !Feature.usesExpiration(context.generateFeatures));
  }

  @Override
  public void execute(LocalCacheContext context) {
    context.constructor.addStatement("this.pacer = ($1L == $2L)\n? null\n: new Pacer($1L)",
        "builder.getScheduler()", "Scheduler.disabledScheduler()");
    context.classSpec.addField(FieldSpec.builder(PACER, "pacer", Modifier.FINAL).build());
    context.classSpec.addMethod(MethodSpec.methodBuilder("pacer")
        .addModifiers(context.publicFinalModifiers())
        .addStatement("return pacer")
        .returns(PACER)
        .build());
  }
}
