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

import javax.lang.model.element.Modifier;

import com.squareup.javapoet.MethodSpec;

/**
 * Generates a toString method with the custom fields.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddToString extends NodeRule {

  @Override
  protected boolean applies() {
    return isBaseClass();
  }

  @Override
  protected void execute() {
    String statement = "return String.format(\"%s=[key=%s, value=%s, weight=%d, queueType=%,d, "
        + "accessTimeNS=%,d, \"\n+ \"writeTimeNS=%,d, varTimeNs=%,d, prevInAccess=%s, "
        + "nextInAccess=%s, prevInWrite=%s, \"\n+ \"nextInWrite=%s]\", getClass().getSimpleName(), "
        + "getKey(), getValue(), getWeight(), \ngetQueueType(), getAccessTime(), getWriteTime(), "
        + "getVariableTime(), \ngetPreviousInAccessOrder() != null, "
        + "getNextInAccessOrder() != null, \ngetPreviousInWriteOrder() != null, "
        + "getNextInWriteOrder() != null)";

    context.nodeSubtype.addMethod(MethodSpec.methodBuilder("toString")
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .returns(String.class)
        .addStatement(statement)
        .build());
  }
}
