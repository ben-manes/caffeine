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

import static com.github.benmanes.caffeine.cache.Specifications.EXPIRY;
import static com.github.benmanes.caffeine.cache.Specifications.TIMER_WHEEL;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.github.benmanes.caffeine.cache.Rule;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddExpireAfterAccess implements Rule<LocalCacheContext> {

  @Override
  public boolean applies(LocalCacheContext context) {
    return context.generateFeatures.contains(Feature.EXPIRE_ACCESS);
  }

  @Override
  public void execute(LocalCacheContext context) {
    variableExpiration(context);
    fixedExpiration(context);
  }

  private static void fixedExpiration(LocalCacheContext context) {
    context.constructor.addStatement(
        "setExpiresAfterAccessNanos(builder.getExpiresAfterAccessNanos())");
    context.addAcquireReleaseField(long.class, "expiresAfterAccessNanos");
    context.classSpec.addMethod(MethodSpec.methodBuilder("expiresAfterAccess")
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return (timerWheel == null)")
        .returns(boolean.class)
        .build());
  }

  private static void variableExpiration(LocalCacheContext context) {
    context.classSpec.addMethod(MethodSpec.methodBuilder("expiresVariable")
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return (timerWheel != null)")
        .returns(boolean.class)
        .build());

    context.constructor.addStatement("this.expiry = builder.getExpiry(isAsync)");
    context.classSpec.addField(FieldSpec.builder(EXPIRY, "expiry", Modifier.FINAL).build());
    context.classSpec.addMethod(MethodSpec.methodBuilder("expiry")
        .addModifiers(context.publicFinalModifiers())
        .addStatement("return expiry")
        .returns(EXPIRY)
        .build());

    context.constructor.addStatement(
        "this.timerWheel = builder.expiresVariable() ? new $T() : null", TIMER_WHEEL);
    context.classSpec.addField(FieldSpec.builder(TIMER_WHEEL, "timerWheel", Modifier.FINAL).build());
    context.classSpec.addMethod(MethodSpec.methodBuilder("timerWheel")
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return timerWheel")
        .returns(TIMER_WHEEL)
        .build());
  }
}
