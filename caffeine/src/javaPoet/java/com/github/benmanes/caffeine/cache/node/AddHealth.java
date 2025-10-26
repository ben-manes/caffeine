package com.github.benmanes.caffeine.cache.node;

import static com.github.benmanes.caffeine.cache.Specifications.*;
import static com.github.benmanes.caffeine.cache.node.NodeContext.varHandleName;

import com.github.benmanes.caffeine.cache.node.NodeContext.Strength;
import com.palantir.javapoet.MethodSpec;

/**
 * Adds the health state to the node.
 * 
 * Refactor versión — ahora cumple con el Principio de Responsabilidad Única (SRP).
 * 
 * Cambios realizados:
 * 1. Separación de responsabilidades en clases auxiliares.
 * 2. Eliminación de lógica mezclada (decisión, mapeo y generación).
 * 3. Delegación clara de tareas para reducir el acoplamiento.
 * 
 * @author Domenica
 */
public final class AddHealth implements NodeRule {

  // ✅ NUEVO: ahora AddHealth delega las tareas a dos ayudantes
  private final HealthStateMapper mapper;       // Encargado de determinar los estados (retired/dead)
  private final HealthMethodGenerator generator; // Encargado de generar los métodos JavaPoet

  /** 
   * ✅ Constructor agregado para inicializar las clases auxiliares 
   * Se evita que AddHealth asuma toda la lógica directamente.
   */
  public AddHealth() {
    this.mapper = new HealthStateMapper();
    this.generator = new HealthMethodGenerator();
  }

  /**
   * Mantiene una única responsabilidad: decidir si se aplica la regla.
   */
  @Override
  public boolean applies(NodeContext context) {
    return context.isBaseClass(); // ✅ Sin cambios — sigue cumpliendo su propósito original
  }

  /**
   * ✅ Cambiado: ahora solo coordina la ejecución, sin contener la lógica interna.
   * Antes este método hacía tres tareas distintas.
   */
  @Override
  public void execute(NodeContext context) {
    // 1️⃣ Mapea los estados de salud (retired/dead)
    HealthConstants constants = mapper.map(context);

    // 2️⃣ Genera los métodos "isAlive", "retire" y "die"
    generator.generate(context, constants);
  }

  // ===============================================================
  // ✅ NUEVA CLASE INTERNA: Encapsula la lógica de mapeo de estados
  // ===============================================================
  private static class HealthStateMapper {

    /**
     * Devuelve los valores correctos según el tipo de clave (STRONG o WEAK).
     */
    public HealthConstants map(NodeContext context) {
      if (context.keyStrength() == Strength.STRONG) {
        return new HealthConstants(RETIRED_STRONG_KEY, DEAD_STRONG_KEY);
      }
      return new HealthConstants(RETIRED_WEAK_KEY, DEAD_WEAK_KEY);
    }
  }

  // ===============================================================
  // ✅ NUEVA CLASE INTERNA: Genera el código correspondiente
  // ===============================================================
  private static class HealthMethodGenerator {

    /**
     * Genera los métodos de estado ("isAlive", "isRetired", "retire", "die")
     */
    public void generate(NodeContext context, HealthConstants constants) {

      // Método "isAlive" — se mantiene igual, pero encapsulado aquí
      context.nodeSubtype.addMethod(MethodSpec.methodBuilder("isAlive")
          .addStatement("Object key = getKeyReference()")
          .addStatement("return (key != $L) && (key != $L)",
              constants.retiredArg(), constants.deadArg())
          .addModifiers(context.publicFinalModifiers())
          .returns(boolean.class)
          .build());

      // Se agregan los demás métodos con ayuda de un método auxiliar privado
      addState(context, "isRetired", "retire", constants.retiredArg(), false);
      addState(context, "isDead", "die", constants.deadArg(), true);
    }

    /**
     * ✅ Método trasladado desde la clase principal (antes dentro de AddHealth)
     * Mantiene la generación de código, pero ahora separada del control de lógica.
     */
    private void addState(NodeContext context, String checkName,
                          String actionName, String arg, boolean finalized) {
      // Método "isRetired" o "isDead"
      context.nodeSubtype.addMethod(MethodSpec.methodBuilder(checkName)
          .addStatement("return (getKeyReference() == $L)", arg)
          .addModifiers(context.publicFinalModifiers())
          .returns(boolean.class)
          .build());

      // Método "retire" o "die"
      var action = MethodSpec.methodBuilder(actionName)
          .addModifiers(context.publicFinalModifiers());

      // Diferencia entre claves y valores fuertes/débiles
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

  // ===============================================================
  // ✅ NUEVO: Record para encapsular las constantes del estado de salud
  // ===============================================================
  private static record HealthConstants(String retiredArg, String deadArg) {}
}
