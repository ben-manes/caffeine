/*
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

import static com.github.benmanes.caffeine.cache.Specifications.NODE;
import static com.github.benmanes.caffeine.cache.Specifications.kRefQueueType;
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.keyRefQueueSpec;
import static com.github.benmanes.caffeine.cache.Specifications.keyRefSpec;
import static com.github.benmanes.caffeine.cache.Specifications.keySpec;
import static com.github.benmanes.caffeine.cache.Specifications.lookupKeyType;
import static com.github.benmanes.caffeine.cache.Specifications.referenceKeyType;
import static com.github.benmanes.caffeine.cache.Specifications.valueRefQueueSpec;
import static com.github.benmanes.caffeine.cache.Specifications.valueSpec;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.github.benmanes.caffeine.cache.Rule;
import com.google.common.collect.ImmutableList;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;

/**
 * @author github.com/jvassev (Julian Vassev)
 */
public final class AddFactoryMethods implements Rule<NodeContext> {

  @Override
  public boolean applies(NodeContext context) {
    return true;
  }

  @Override
  public void execute(NodeContext context) {
    addFactories(context);

    if (context.generateFeatures.contains(Feature.WEAK_KEYS)) {
      addWeakKeys(context);
    }
    if (context.generateFeatures.contains(Feature.WEAK_VALUES)) {
      addWeakValues(context);
    } else if (context.generateFeatures.contains(Feature.SOFT_VALUES)) {
      addSoftValues(context);
    }
  }

  private static void addFactories(NodeContext context) {
    context.classSpec.addMethod(
        newNode(keySpec, keyRefQueueSpec)
            .addStatement("return new $N<>(key, keyReferenceQueue, value, "
                + "valueReferenceQueue, weight, now)", context.className)
            .build());
    context.classSpec.addMethod(
        newNode(keyRefSpec)
            .addStatement("return new $N<>(keyReference, value, valueReferenceQueue, weight, now)",
                context.className)
            .build());
  }

  private static void addWeakKeys(NodeContext context) {
    context.classSpec.addMethod(MethodSpec.methodBuilder("newLookupKey")
        .addModifiers(Modifier.PUBLIC)
        .addParameter(Object.class, "key")
        .addStatement("return new $T<>(key)", lookupKeyType)
        .returns(Object.class)
        .build());
    context.classSpec.addMethod(MethodSpec.methodBuilder("newReferenceKey")
        .addModifiers(Modifier.PUBLIC)
        .addParameter(kTypeVar, "key")
        .addParameter(kRefQueueType, "referenceQueue")
        .addStatement("return new $T($L, $L)", referenceKeyType, "key", "referenceQueue")
        .returns(Object.class)
        .build());
  }

  private static void addSoftValues(NodeContext context) {
    context.classSpec.addMethod(MethodSpec.methodBuilder("softValues")
        .addModifiers(Modifier.PUBLIC)
        .addStatement("return true")
        .returns(boolean.class)
        .build());
  }

  private static void addWeakValues(NodeContext context) {
    context.classSpec.addMethod(MethodSpec.methodBuilder("weakValues")
        .addModifiers(Modifier.PUBLIC)
        .addStatement("return true")
        .returns(boolean.class)
        .build());
  }

  private static MethodSpec.Builder newNode(ParameterSpec... keyParams) {
    return MethodSpec.methodBuilder("newNode")
        .addModifiers(Modifier.PUBLIC)
        .addParameters(ImmutableList.copyOf(keyParams))
        .addParameter(valueSpec)
        .addParameter(valueRefQueueSpec)
        .addParameter(int.class, "weight")
        .addParameter(long.class, "now")
        .returns(NODE);
  }
}
