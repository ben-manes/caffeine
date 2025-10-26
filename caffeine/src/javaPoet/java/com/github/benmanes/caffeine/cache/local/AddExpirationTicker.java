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

import static com.github.benmanes.caffeine.cache.Specifications.TICKER;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.github.benmanes.caffeine.cache.Rule;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddExpirationTicker implements Rule<LocalCacheContext> {

  @Override
  public boolean applies(LocalCacheContext context) {
    return !(Feature.usesExpirationTicker(context.parentFeatures)
        || !Feature.usesExpirationTicker(context.generateFeatures));
  }

  @Override
  public void execute(LocalCacheContext context) {
    context.constructor.addStatement("this.ticker = builder.getTicker()");
    context.classSpec.addField(FieldSpec.builder(TICKER, "ticker", Modifier.FINAL).build());
    context.classSpec.addMethod(MethodSpec.methodBuilder("expirationTicker")
        .addModifiers(context.publicFinalModifiers())
        .addStatement("return ticker")
        .returns(TICKER)
        .build());
  }
}
