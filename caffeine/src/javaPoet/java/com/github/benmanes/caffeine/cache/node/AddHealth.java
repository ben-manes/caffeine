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

import static com.github.benmanes.caffeine.cache.Specifications.DEAD_STRONG_KEY;
import static com.github.benmanes.caffeine.cache.Specifications.DEAD_WEAK_KEY;
import static com.github.benmanes.caffeine.cache.Specifications.RETIRED_STRONG_KEY;
import static com.github.benmanes.caffeine.cache.Specifications.RETIRED_WEAK_KEY;

import java.lang.ref.Reference;

import com.squareup.javapoet.MethodSpec;

/**
 * Adds the health state to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddHealth extends NodeRule {

  @Override
  protected boolean applies() {
    return isBaseClass();
  }

  @Override
  protected void execute() {
    String retiredArg;
    String deadArg;
    if (keyStrength() == Strength.STRONG) {
      retiredArg = RETIRED_STRONG_KEY;
      deadArg = DEAD_STRONG_KEY;
    } else {
      retiredArg = RETIRED_WEAK_KEY;
      deadArg = DEAD_WEAK_KEY;
    }

    context.nodeSubtype.addMethod(MethodSpec.methodBuilder("isAlive")
        .addStatement("Object key = getKeyReference()")
        .addStatement("return (key != $L) && (key != $L)", retiredArg, deadArg)
        .addModifiers(context.publicFinalModifiers())
        .returns(boolean.class)
        .build());
    addState("isRetired", "retire", retiredArg, false);
    addState("isDead", "die", deadArg, true);
  }

  private void addState(String checkName, String actionName, String arg, boolean finalized) {
    context.nodeSubtype.addMethod(MethodSpec.methodBuilder(checkName)
        .addStatement("return (getKeyReference() == $L)", arg)
        .addModifiers(context.publicFinalModifiers())
        .returns(boolean.class)
        .build());

    MethodSpec.Builder action = MethodSpec.methodBuilder(actionName)
        .addModifiers(context.publicFinalModifiers());
    if (keyStrength() != Strength.STRONG) {
      action.addStatement("(($T<K>) getKeyReference()).clear()", Reference.class);
    }
    if (valueStrength() == Strength.STRONG) {
      // Set the value to null only when dead, as otherwise the explicit removal of an expired async
      // value will be notified as explicit rather than expired due to the isComputingAsync() check
      if (finalized) {
        action.addStatement("$L.set(this, null)", varHandleName("value"));
      }
    } else {
      action.addStatement("(($T<V>) getValueReference()).clear()", Reference.class);
    }
    action.addStatement("$L.set(this, $N)", varHandleName("key"), arg);
    context.nodeSubtype.addMethod(action.build());
  }
}
