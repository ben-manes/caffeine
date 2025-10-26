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

import static com.github.benmanes.caffeine.cache.Specifications.LOCAL_CACHE_FACTORY;
import static org.apache.commons.lang3.StringUtils.capitalize;

import java.util.Set;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.github.benmanes.caffeine.cache.RuleContext;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LocalCacheContext extends RuleContext {
  final MethodSpec.Builder constructor;

  public LocalCacheContext(TypeName superClass, String className, boolean isFinal,
      Set<Feature> parentFeatures, Set<Feature> generateFeatures) {
    super(superClass, className, isFinal, parentFeatures, generateFeatures);
    this.constructor = MethodSpec.constructorBuilder();
  }

  public void addAcquireReleaseField(Class<?> type, String name) {
    addVarHandle(LOCAL_CACHE_FACTORY, name, TypeName.get(type));
    classSpec.addField(FieldSpec.builder(type, name, Modifier.VOLATILE).build());
    classSpec.addMethod(MethodSpec.methodBuilder(name)
        .addModifiers(protectedFinalModifiers())
        .addStatement("return ($L) $L.getAcquire(this)", type, varHandleName(name))
        .returns(type)
        .build());
    classSpec.addMethod(MethodSpec.methodBuilder("set" + capitalize(name))
        .addModifiers(protectedFinalModifiers())
        .addParameter(type, name)
        .addStatement("$L.setRelease(this, $N)", varHandleName(name), name)
        .build());
  }
}
