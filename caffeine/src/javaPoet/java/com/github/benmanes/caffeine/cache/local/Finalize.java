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

import static com.github.benmanes.caffeine.cache.Specifications.LOOKUP;
import static com.github.benmanes.caffeine.cache.Specifications.METHOD_HANDLES;

import org.apache.commons.lang3.StringUtils;

import com.github.benmanes.caffeine.cache.Rule;
import com.github.benmanes.caffeine.cache.RuleContext;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.CodeBlock;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Finalize implements Rule<LocalCacheContext> {

  @Override
  public boolean applies(LocalCacheContext context) {
    return true;
  }

  @Override
  public void execute(LocalCacheContext context) {
    context.classSpec.addMethod(context.constructor.build());
    addSuppressedWarnings(context);
    addStaticBlock(context);
  }

  private static void addSuppressedWarnings(RuleContext context) {
    if (!context.suppressedWarnings.isEmpty()) {
      var format = (context.suppressedWarnings.size() == 1)
          ? "$S"
          : "{" + StringUtils.repeat("$S", ", ", context.suppressedWarnings.size()) + "}";
      context.classSpec.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
          .addMember("value", format, context.suppressedWarnings.toArray())
          .build());
    }
  }

  private static void addStaticBlock(RuleContext context) {
    if (context.varHandles().isEmpty()) {
      return;
    }
    var codeBlock = CodeBlock.builder()
        .addStatement("$T lookup = $T.lookup()", LOOKUP, METHOD_HANDLES)
        .beginControlFlow("try");
    for (var varHandle : context.varHandles()) {
      varHandle.accept(codeBlock);
    }
    codeBlock
        .nextControlFlow("catch ($T e)", ReflectiveOperationException.class)
          .addStatement("throw new ExceptionInInitializerError(e)")
        .endControlFlow();
    context.classSpec.addStaticBlock(codeBlock.build());
  }
}
