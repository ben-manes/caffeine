/*
 * Copyright 2025 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache;

import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import javax.lang.model.element.Modifier;

import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class RuleContext {
  private final List<Consumer<CodeBlock.Builder>> varHandles;

  public final ImmutableSet<Feature> generateFeatures;
  public final ImmutableSet<Feature> parentFeatures;
  public final Set<String> suppressedWarnings;
  public final TypeSpec.Builder classSpec;
  public final TypeName superClass;
  public final String className;
  public final boolean isFinal;

  protected RuleContext(TypeName superClass, String className, boolean isFinal,
      Set<Feature> parentFeatures, Set<Feature> generateFeatures) {
    this.generateFeatures = Sets.immutableEnumSet(generateFeatures);
    this.parentFeatures = Sets.immutableEnumSet(parentFeatures);
    this.classSpec = TypeSpec.classBuilder(className);
    this.suppressedWarnings = new TreeSet<>();
    this.varHandles = new ArrayList<>();
    this.superClass = superClass;
    this.className = className;
    this.isFinal = isFinal;
  }

  public List<Consumer<CodeBlock.Builder>> varHandles() {
    return varHandles;
  }

  public TypeSpec build() {
    return classSpec.build();
  }

  public Modifier[] publicFinalModifiers() {
    return isFinal
        ? new Modifier[] { Modifier.PUBLIC }
        : new Modifier[] { Modifier.PUBLIC, Modifier.FINAL };
  }

  public Modifier[] protectedFinalModifiers() {
    return isFinal
        ? new Modifier[] { Modifier.PROTECTED }
        : new Modifier[] { Modifier.PROTECTED, Modifier.FINAL };
  }

  /** Creates a VarHandle to the instance field. */
  public void addVarHandle(ClassName factory, String varName, TypeName type) {
    String fieldName = varHandleName(varName);
    classSpec.addField(FieldSpec.builder(VarHandle.class, fieldName,
        isFinal ? Modifier.PRIVATE : Modifier.PROTECTED, Modifier.STATIC, Modifier.FINAL).build());
    Consumer<CodeBlock.Builder> statement = builder -> builder
        .addStatement("$L = lookup.findVarHandle($T.class, $L.$L, $T.class)", fieldName,
            ClassName.bestGuess(className), factory.simpleName(),
            fieldName, type);
    varHandles.add(statement);
  }

  /** Returns the name of the VarHandle to this variable. */
  public static String varHandleName(String varName) {
    return CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, varName);
  }
}
