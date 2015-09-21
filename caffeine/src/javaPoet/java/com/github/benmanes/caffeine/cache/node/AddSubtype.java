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
package com.github.benmanes.caffeine.cache.node;

import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.nodeType;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;

import javax.lang.model.element.Modifier;

import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;

/**
 * Adds the node inheritance hierarchy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddSubtype extends NodeRule {

  @Override
  protected boolean applies() {
    return true;
  }

  @Override
  protected void execute() {
    context.nodeSubtype = TypeSpec.classBuilder(context.className)
        .addModifiers(Modifier.STATIC)
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar);
    if (context.isFinal) {
      context.nodeSubtype.addModifiers(Modifier.FINAL);
    }
    if (isBaseClass()) {
      context.nodeSubtype.addSuperinterface(
          ParameterizedTypeName.get(nodeType, kTypeVar, vTypeVar));
    } else {
      context.nodeSubtype.superclass(context.superClass);
    }
  }
}
