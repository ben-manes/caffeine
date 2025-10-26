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

import static com.github.benmanes.caffeine.cache.Specifications.*;
import static com.github.benmanes.caffeine.cache.node.NodeContext.varHandleName;

import com.github.benmanes.caffeine.cache.node.NodeContext.Strength;
import com.palantir.javapoet.MethodSpec;

/**
 * Refactor: Clase AddHealth corregida para cumplir con SRP.
 * La responsabilidad de determinar constantes y generar métodos
 * se separó en clases auxiliares internas.
 */
public final class AddHealth implements NodeRule {

  private final HealthStateMapper stateMapper = new HealthStateMapper(); // ← NUEVO
  private final HealthMethodGenerator methodGenerator = new HealthMethodGenerator(); // ← NUEVO

  @Override
  public boolean applies(NodeContext context) {
    return context.isBaseClass();
  }

  @Override
  public void execute(NodeContext context) {
    // Línea refactorizada: responsabilidad delegada
    HealthConstants constants = stateMapper.mapConstants(context);

    // Generación de métodos delegada
    methodGenerator.addIsAlive(context, constants);
    methodGenerator.addStateMethods(context, constants);
  }

  // ----------------------- NUEVAS CLASES INTERNAS -----------------------

  /** Clase auxiliar que define las constantes según la fuerza de la clave */
  private static class HealthStateMapper { // ← NUEVO BLOQUE
    public HealthConstants mapConstants(NodeContext context) {
      if (context.keyStrength() == Strength.STRONG) {
        return new HealthConstants(RETIRED_STRONG_KEY, DEAD_STRONG_KEY);
      }
      return new HealthConstants(RETIRED_WEAK_KEY, DEAD_WEAK_KEY);
    }
  }

  /** Clase auxiliar que genera los métodos del estado de salud */
  private static class HealthMethodGenerator { // ← NUEVO BLOQUE
    public void addIsAlive(NodeContext context, HealthConstants constants) {
      context.nodeSubtype.addMethod(MethodSpec.methodBuilder("isAlive")
          .addStatement("Object key = getKeyReference()")
          .addStatement("return (key != $L) && (key != $L)",
              constants.retiredKey(), constants.deadKey())
          .addModifiers(context.publicFinalModifiers())
          .returns(boolean.class)
          .build());
    }

    public void addStateMethods(NodeContext context, HealthConstants constants) {
      addState(context, "isRetired", "retire", constants.retiredKey(), false);
      addState(context, "isDead", "die", constants.deadKey(), true);
    }

    private static void addState(NodeContext context, String checkName,
        String actionName, String arg, boolean finalized) {
      context.nodeSubtype.addMethod(MethodSpec.methodBuilder(checkName)
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
      context.nodeSubtype.addMethod(action.build());
    }
  }

  /** Clase contenedora de las constantes de salud */
  private static record HealthConstants(String retiredKey, String deadKey) {} // ← NUEVO BLOQUE
}
