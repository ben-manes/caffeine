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
package com.github.benmanes.caffeine.cache.local;

import static com.github.benmanes.caffeine.cache.RuleContext.varHandleName;
import static com.github.benmanes.caffeine.cache.Specifications.ASYNC_CACHE_LOADER_PARAM;
import static com.github.benmanes.caffeine.cache.Specifications.BUILDER_PARAM;
import static com.github.benmanes.caffeine.cache.Specifications.FREQUENCY_SKETCH;
import static com.github.benmanes.caffeine.cache.Specifications.LOCAL_CACHE_FACTORY;
import static com.github.benmanes.caffeine.cache.Specifications.WINDOW_CLIMBER;
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;
import static org.apache.commons.lang3.StringUtils.capitalize;

import java.util.Set;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.github.benmanes.caffeine.cache.Rule;
import com.github.benmanes.caffeine.cache.RuleContext;
import com.google.common.collect.ImmutableList;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddMaximum implements Rule<LocalCacheContext> {

  @Override
  public boolean applies(LocalCacheContext context) {
    return Feature.addsMaximum(context.parentFeatures, context.generateFeatures);
  }

  @Override
  public void execute(LocalCacheContext context) {
    addEvicts(context);
    addMaximumSize(context);
  }

  /**
   * Returns the classes to insert between a cache and its parent, which keep the maximums and
   * weighted sizes off the cache lines of the fields that a read loads.
   */
  public static ImmutableList<TypeSpec> supertypes(String parentClassName, TypeName parent,
      Set<Feature> parentFeatures, Set<Feature> generateFeatures) {
    if (Feature.addsMaximum(parentFeatures, generateFeatures)) {
      var policy = addEvictionPolicy(parentClassName + "EvictionPolicy", parent,
          parentFeatures, generateFeatures);
      var padding = addPadding(parentClassName + "PadBeforeMaximum", typeOf(policy),
          /* first= */ 120, "Pads the maximums and weighted sizes of its subclasses from the "
              + "fields of its super classes.");
      return ImmutableList.of(policy, padding);
    } else if (Feature.usesMaximum(parentFeatures)
        && !Feature.usesExpirationTicker(parentFeatures)) {
      return ImmutableList.of(addPadding(parentClassName + "PadAfterMaximum", parent,
          /* first= */ 135, "Pads the maximums and weighted sizes of {@link " + parentClassName
              + "} from the fields of its subclasses."));
    }
    return ImmutableList.of();
  }

  /** Returns a class holding the eviction policy's climber, sketch and access-order deques. */
  private static TypeSpec addEvictionPolicy(String className, TypeName superClass,
      Set<Feature> parentFeatures, Set<Feature> generateFeatures) {
    var context = new LocalCacheContext(superClass, className,
        /* isFinal= */ false, parentFeatures, generateFeatures);
    addSuperConstructor(context.constructor);
    addHillClimber(context);
    addFrequencySketch(context);
    AddDeques.addAccessOrderDeques(context);
    return supertype(context.classSpec, context.constructor, superClass,
        "Holds the eviction policy of a cache bounded by size or weight, which only the "
        + "maintenance thread uses.");
  }

  /** Returns a class of 15 longs (120 bytes) of padding, named from {@code p<first>}. */
  private static TypeSpec addPadding(String className,
      TypeName superClass, int first, String javadoc) {
    var classSpec = TypeSpec.classBuilder(className);
    for (int i = first; i < first + 15; i++) {
      classSpec.addField(long.class, "p" + i);
    }
    var constructor = MethodSpec.constructorBuilder();
    addSuperConstructor(constructor);
    return supertype(classSpec, constructor, superClass, javadoc);
  }

  private static void addSuperConstructor(MethodSpec.Builder constructor) {
    constructor
        .addParameter(BUILDER_PARAM)
        .addParameter(ASYNC_CACHE_LOADER_PARAM)
        .addParameter(boolean.class, "async")
        .addStatement("super(builder, cacheLoader, async)");
  }

  private static TypeSpec supertype(TypeSpec.Builder classSpec,
      MethodSpec.Builder constructor, TypeName superClass, String javadoc) {
    return classSpec
        .addJavadoc("<em>WARNING: GENERATED CODE</em>\n\n$L\n\n", javadoc)
        .addJavadoc("@author ben.manes@gmail.com (Ben Manes)\n")
        .addModifiers(Modifier.ABSTRACT)
        .superclass(superClass)
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar)
        .addMethod(constructor.build())
        .build();
  }

  private static TypeName typeOf(TypeSpec type) {
    return ParameterizedTypeName.get(ClassName.bestGuess(type.name()), kTypeVar, vTypeVar);
  }

  private static void addEvicts(LocalCacheContext context) {
    context.classSpec.addMethod(MethodSpec.methodBuilder("evicts")
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return true")
        .returns(boolean.class)
        .build());
  }

  private static void addMaximumSize(LocalCacheContext context) {
    addPlainAndAcquireField(context, long.class, "maximum");
    addPlainAndAcquireField(context, long.class, "weightedSize");

    addField(context, long.class, "windowMaximum");
    addField(context, long.class, "windowWeightedSize");
    addField(context, long.class, "mainProtectedMaximum");
    addField(context, long.class, "mainProtectedWeightedSize");
  }

  private static void addHillClimber(LocalCacheContext context) {
    context.classSpec.addField(FieldSpec.builder(
        WINDOW_CLIMBER, "climber", Modifier.FINAL).build());
    context.constructor.addCode(CodeBlock.builder()
        .addStatement("this.climber = new $T()", WINDOW_CLIMBER)
        .addStatement("this.climber.resized(maximum())")
        .build());
    context.classSpec.addMethod(MethodSpec.methodBuilder("climber")
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return climber")
        .returns(WINDOW_CLIMBER)
        .build());
  }

  private static void addFrequencySketch(LocalCacheContext context) {
    context.classSpec.addField(FieldSpec.builder(
        FREQUENCY_SKETCH, "sketch", Modifier.FINAL).build());
    context.constructor.addCode(CodeBlock.builder()
        .addStatement("this.sketch = new $T()", FREQUENCY_SKETCH)
        .beginControlFlow("if (builder.hasInitialCapacity())")
            .addStatement("long capacity = Math.min($L, $L)",
                "builder.getMaximum()", "builder.getInitialCapacity()")
            .addStatement("this.sketch.ensureCapacity(capacity)")
            .addStatement("recordReads()")
        .endControlFlow().build());
    context.classSpec.addMethod(MethodSpec.methodBuilder("frequencySketch")
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return sketch")
        .returns(FREQUENCY_SKETCH)
        .build());
  }

  private static void addField(RuleContext context, Class<?> type, String name) {
    context.classSpec.addField(FieldSpec.builder(type, name).build());
    context.classSpec.addMethod(MethodSpec.methodBuilder(name)
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return $L", name)
        .returns(type)
        .build());
    context.classSpec.addMethod(MethodSpec.methodBuilder("set" + capitalize(name))
        .addModifiers(context.protectedFinalModifiers())
        .addParameter(type, name)
        .addStatement("this.$1L = $1L", name)
        .build());
  }

  private static void addPlainAndAcquireField(RuleContext context, Class<?> type, String name) {
    context.addVarHandle(LOCAL_CACHE_FACTORY, name, TypeName.get(type));
    context.classSpec.addField(FieldSpec.builder(type, name, Modifier.VOLATILE).build());
    context.classSpec.addMethod(MethodSpec.methodBuilder(name)
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return ($L) $L.get(this)", type, varHandleName(name))
        .returns(type)
        .build());
    context.classSpec.addMethod(MethodSpec.methodBuilder(name + "Acquire")
        .addModifiers(context.protectedFinalModifiers())
        .addStatement("return ($L) $L.getAcquire(this)",
            type, varHandleName(name))
        .returns(type)
        .build());
    context.classSpec.addMethod(MethodSpec.methodBuilder("set" + capitalize(name))
        .addModifiers(context.protectedFinalModifiers())
        .addParameter(type, name)
        .addStatement("$L.setRelease(this, $N)", varHandleName(name), name)
        .build());
  }
}
