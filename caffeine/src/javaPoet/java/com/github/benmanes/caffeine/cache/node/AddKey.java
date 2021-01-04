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

import javax.lang.model.element.Modifier;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;

/**
 * Adds the key to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AddKey extends NodeRule {

  @Override
  protected boolean applies() {
    return isBaseClass();
  }

  @Override
  protected void execute() {
    context.nodeSubtype
        .addField(newKeyField())
        .addMethod(newGetter(keyStrength(), kTypeVar, "key", Visibility.PLAIN))
        .addMethod(newGetRef("key"));
    addVarHandle("key", isStrongKeys()
        ? ClassName.get(Object.class)
        : keyReferenceType().rawType);
  }

  private FieldSpec newKeyField() {
    FieldSpec.Builder fieldSpec = isStrongKeys()
        ? FieldSpec.builder(kTypeVar, "key", Modifier.VOLATILE)
        : FieldSpec.builder(keyReferenceType(), "key", Modifier.VOLATILE);
    return fieldSpec.build();
  }
}
