package com.github.benmanes.caffeine.cache.node;

import com.github.benmanes.caffeine.cache.Feature;
import com.squareup.javapoet.MethodSpec;

import javax.lang.model.element.Modifier;

import static com.github.benmanes.caffeine.cache.Specifications.NODE2;
import static com.github.benmanes.caffeine.cache.Specifications.kRefQueueType2;
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar2;
import static com.github.benmanes.caffeine.cache.Specifications.keyRefQueueSpec2;
import static com.github.benmanes.caffeine.cache.Specifications.keyRefSpec;
import static com.github.benmanes.caffeine.cache.Specifications.keySpec2;
import static com.github.benmanes.caffeine.cache.Specifications.lookupKeyType2;
import static com.github.benmanes.caffeine.cache.Specifications.referenceKeyType2;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar2;
import static com.github.benmanes.caffeine.cache.Specifications.valueRefQueueSpec2;
import static com.github.benmanes.caffeine.cache.Specifications.valueSpec2;

public class AddFactoryMethods extends NodeRule {
  @Override
  protected boolean applies() {
    return true;
  }

  @Override
  protected void execute() {
    String statementWithKey = makeFactoryStatementKey();
    String statementWithKeyRef = makeFactoryStatementKeyRef();
    Object className = context.className;

    context.nodeSubtype
        .addMethod(newNodeByKey()
            .addStatement(statementWithKey, className).build())
        .addMethod(newNodeByKeyRef()
            .addStatement(statementWithKeyRef, className).build());

    if (context.generateFeatures.contains(Feature.WEAK_KEYS)) {
      context.nodeSubtype
          .addMethod(makeNewLookupKey())
          .addMethod(makeReferenceKey());
    }
    if (context.generateFeatures.contains(Feature.WEAK_VALUES)) {
      context.nodeSubtype
          .addMethod(makeWeakValues());
    }
    if (context.generateFeatures.contains(Feature.SOFT_VALUES)) {
      context.nodeSubtype
          .addMethod(makeSoftValues());
    }
  }

  private MethodSpec makeSoftValues() {
    return MethodSpec.methodBuilder("softValues")
        .addModifiers(Modifier.PUBLIC)
        .addStatement("return true")
        .returns(boolean.class)
        .build();
  }

  private MethodSpec makeWeakValues() {
    return MethodSpec.methodBuilder("weakValues")
        .addModifiers(Modifier.PUBLIC)
        .addStatement("return true")
        .returns(boolean.class)
        .build();
  }

  private MethodSpec makeNewLookupKey() {
    return MethodSpec.methodBuilder("newLookupKey")
        .addModifiers(Modifier.PUBLIC)
        .addTypeVariable(kTypeVar2)
        .addParameter(kTypeVar2, "key")
        .addStatement("return new $T(key)", lookupKeyType2)
        .returns(Object.class)
        .build();
  }

  private MethodSpec.Builder newNodeByKey() {
    return completeNewNode(MethodSpec.methodBuilder("newNode")
            .addModifiers(Modifier.PUBLIC)
            .addParameter(keySpec2)
            .addParameter(keyRefQueueSpec2));
  }

  private MethodSpec.Builder newNodeByKeyRef() {
    return completeNewNode(MethodSpec.methodBuilder("newNode")
            .addModifiers(Modifier.PUBLIC)
            .addParameter(keyRefSpec));
  }

  private static MethodSpec.Builder completeNewNode(MethodSpec.Builder method) {
    return method
            .addTypeVariable(kTypeVar2)
            .addTypeVariable(vTypeVar2)
            .addParameter(valueSpec2)
            .addParameter(valueRefQueueSpec2)
            .addParameter(int.class, "weight")
            .addParameter(long.class, "now")
            .returns(NODE2);
  }

  private MethodSpec makeReferenceKey() {
    return MethodSpec.methodBuilder("newReferenceKey")
        .addModifiers(Modifier.PUBLIC)
        .addTypeVariable(kTypeVar2)
        .addParameter(kTypeVar2, "key")
        .addParameter(kRefQueueType2, "referenceQueue")
        .addStatement("return new $T($L, $L)", referenceKeyType2, "key", "referenceQueue")
        .returns(Object.class)
        .build();
  }

  private String makeFactoryStatementKey() {
    return "return new $N<>(key, keyReferenceQueue, value, valueReferenceQueue, weight, now)";
  }

  private String makeFactoryStatementKeyRef() {
    return "return new $N<>(keyReference, value, valueReferenceQueue, weight, now)";
  }
}
