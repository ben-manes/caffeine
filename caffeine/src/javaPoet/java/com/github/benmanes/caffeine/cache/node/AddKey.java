package com.github.benmanes.caffeine.cache.node;

import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.referenceType;
import static com.github.benmanes.caffeine.cache.node.NodeContext.varHandleName;

import javax.lang.model.element.Modifier;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.node.NodeContext.Strength;
import com.github.benmanes.caffeine.cache.node.NodeContext.Visibility;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;

/**
 * Adds the key to the node.
 * Refactored to comply with the Interface Segregation Principle (ISP),
 * depending only on KeyContext instead of INodeContext.
 *
 * @author 
 */
public final class AddKey implements NodeRule {

  @Override
  public boolean applies(NodeContext context) {
    return context.isBaseClass();
  }

  @Override
  public void execute(KeyContext context) {
    // Determina el tipo de clave y genera los métodos y campos necesarios.
    if (context.isStrongKeys()) {
      addIfStrongValue(context);
    } else {
      addIfCollectedValue(context);
    }
  }

  /** Maneja el caso de claves fuertes */
  private static void addIfStrongValue(KeyContext context) {
    var fieldSpec = FieldSpec.builder(
        context.isStrongKeys() ? kTypeVar : context.keyReferenceType(),
        "key",
        Modifier.VOLATILE
    );

    context.nodeSubtype
        .addField(fieldSpec.build())
        .addMethod(MethodSpec.methodBuilder("getKey")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .returns(kTypeVar)
            .addStatement("return this.key")
            .build())
        .addMethod(MethodSpec.methodBuilder("getKeyReference")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .returns(Object.class)
            .addStatement("return $L.getOpaque(this)", varHandleName("key"))
            .build());

    context.addVarHandle("key", context.isStrongKeys()
        ? ClassName.get(Object.class)
        : context.keyReferenceType().rawType());
  }

  /** Maneja el caso de claves débiles o referenciadas */
  private static void addIfCollectedValue(KeyContext context) {
    var getKeyReference = MethodSpec.methodBuilder("getKeyReference")
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .returns(Object.class)
        .addStatement("$1T keyRef = ($1T) $2L.getAcquire(this)",
            referenceType, varHandleName("key"))
        .addStatement("return (keyRef.get() == null) ? null : keyRef")
        .build();

    context.nodeSubtype.addMethod(getKeyReference);

    var getKey = MethodSpec.methodBuilder("getKey")
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .returns(kTypeVar)
        .addStatement("$1T keyRef = ($1T) $2L.getAcquire(this)",
            referenceType, varHandleName("key"))
        .addStatement("return ($T) keyRef.get()", kTypeVar)
        .build();

    context.nodeSubtype.addMethod(getKey);
  }
}

