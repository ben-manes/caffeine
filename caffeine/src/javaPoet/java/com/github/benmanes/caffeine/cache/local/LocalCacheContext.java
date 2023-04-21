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

import java.util.Set;
import java.util.TreeSet;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LocalCacheContext {
  public final boolean isFinal;
  public final String className;
  public final TypeName superClass;
  public final TypeSpec.Builder cache;
  public final MethodSpec.Builder constructor;
  public final Set<String> suppressedWarnings;
  public final ImmutableSet<Feature> parentFeatures;
  public final ImmutableSet<Feature> generateFeatures;

  public LocalCacheContext(TypeName superClass, String className, boolean isFinal,
      Set<Feature> parentFeatures, Set<Feature> generateFeatures) {
    this.isFinal = isFinal;
    this.className = className;
    this.superClass = superClass;
    this.suppressedWarnings = new TreeSet<>();
    this.cache = TypeSpec.classBuilder(className);
    this.constructor = MethodSpec.constructorBuilder();
    this.parentFeatures = Sets.immutableEnumSet(parentFeatures);
    this.generateFeatures = Sets.immutableEnumSet(generateFeatures);
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
}
