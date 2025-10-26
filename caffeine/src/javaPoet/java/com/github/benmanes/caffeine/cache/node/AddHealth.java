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

import static com.github.benmanes.caffeine.cache.RuleContext.varHandleName;
import static com.github.benmanes.caffeine.cache.Specifications.DEAD_STRONG_KEY;
import static com.github.benmanes.caffeine.cache.Specifications.DEAD_WEAK_KEY;
import static com.github.benmanes.caffeine.cache.Specifications.RETIRED_STRONG_KEY;
import static com.github.benmanes.caffeine.cache.Specifications.RETIRED_WEAK_KEY;
import static com.github.benmanes.caffeine.cache.Specifications.referenceType;

import com.github.benmanes.caffeine.cache.Rule;
import com.github.benmanes.caffeine.cache.node.NodeContext.Strength;
import com.palantir.javapoet.MethodSpec;

/**
 * Adds the health state to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddHealth implements Rule<NodeContext> {

  @Override
  public boolean applies(NodeContext context) {
    return context.isBaseClass();
  }

  @Override
  public void execute(NodeContext context) {
    String retiredArg;
    String deadArg;
    if (context.keyStrength() == Strength.STRONG) {
      retiredArg = RETIRED_STRONG_KEY;
      deadArg = DEAD_STRONG_KEY;
    } else {
      retiredArg = RETIRED_WEAK_KEY;
      deadArg = DEAD_WEAK_KEY;
    }

    context.classSpec.addMethod(MethodSpec.methodBuilder("isAlive")
        .addStatement("Object key = getKeyReference()")
        .addStatement("return (key != $L) && (key != $L)", retiredArg, deadArg)
        .addModifiers(context.publicFinalModifiers())
        .returns(boolean.class)
        .build());
    addState(context, "isRetired", "retire", retiredArg, /* finalized= */ false);
    addState(context, "isDead", "die", deadArg, /* finalized= */ true);
  }

  private static void addState(NodeContext context, String checkName,
      String actionName, String arg, boolean finalized) {
    context.classSpec.addMethod(MethodSpec.methodBuilder(checkName)
        .addStatement("return (getKeyReference() == $L)", arg)
        .addModifiers(context.publicFinalModifiers())
        .returns(boolean.class)
        .build());

    var action = MethodSpec.methodBuilder(actionName)
        .addModifiers(context.publicFinalModifiers());
    if (context.valueStrength() == Strength.STRONG) {
      if (context.keyStrength() != Strength.STRONG) {
        action.addStatement("key.clear()");
      }
      // Set the value to null only when dead, as otherwise the explicit removal of an expired async
      // value will be notified as explicit rather than expired due to the isComputingAsync() check
      if (finalized) {
        action.addStatement("$L.set(this, null)", varHandleName("value"));
      }
      action.addStatement("$L.set(this, $N)", varHandleName("key"), arg);
    } else {
      action.addStatement("$1T valueRef = ($1T) $2L.getOpaque(this)",
          context.valueReferenceType(), varHandleName("value"));
      if (context.keyStrength() != Strength.STRONG) {
        action.addStatement("$1T keyRef = ($1T) valueRef.getKeyReference()", referenceType);
        action.addStatement("keyRef.clear()");
      }
      action.addStatement("valueRef.setKeyReference($N)", arg);
      action.addStatement("valueRef.clear()");
    }
    context.classSpec.addMethod(action.build());
  }
}
