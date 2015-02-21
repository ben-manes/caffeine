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
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.cache.Specifications.PACKAGE_NAME;
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Year;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

/**
 * Generates a factory that creates the cache optimized for the user specified configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LocalCacheFactoryGenerator {
  static final TypeName BUILDER = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "Caffeine"), kTypeVar, vTypeVar);
  static final TypeName MANUAL_CACHE = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "Cache"), kTypeVar, vTypeVar);
  static final TypeName LOADING_CACHE = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "LoadingCache"), kTypeVar, vTypeVar);
  static final TypeName ASYNC_CACHE = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "AsyncLoadingCache"), kTypeVar, vTypeVar);

  final Path directory;
  TypeSpec.Builder factory;

  public LocalCacheFactoryGenerator(Path directory) {
    this.directory = requireNonNull(directory);
  }

  void generate() throws IOException {
    factory = TypeSpec.enumBuilder("LocalCacheFactory");
    addClassJavaDoc();
    addFactoryMethods();

    factory.addEnumConstant("temp");
    writeJavaFile();
  }

  private void addFactoryMethods() {
    factory.addMethod(newManualCache());
    factory.addMethod(newLoadingCache());
    factory.addMethod(newAsyncLoadingCache());
  }

  private MethodSpec newManualCache() {
    MethodSpec.Builder method = newCacheMethod("newManualCache", MANUAL_CACHE);
    method.addStatement("return null");
    return method.build();
  }

  private MethodSpec newLoadingCache() {
    MethodSpec.Builder method = newCacheMethod("newLoadingCache", LOADING_CACHE);
    method.addStatement("return null");
    return method.build();
  }

  private MethodSpec newAsyncLoadingCache() {
    MethodSpec.Builder method = newCacheMethod("newAsyncLoadingCache", ASYNC_CACHE);
    method.addStatement("return null");
    return method.build();
  }

  private MethodSpec.Builder newCacheMethod(String methodName, TypeName returnType) {
    return MethodSpec.methodBuilder(methodName)
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar)
        .addParameter(ParameterSpec.builder(BUILDER, "builder").build())
        .returns(returnType);
  }

  private void writeJavaFile() throws IOException {
    JavaFile.builder(getClass().getPackage().getName(), factory.build())
        .addFileComment("Copyright $L Ben Manes. All Rights Reserved.", Year.now())
        .indent("  ")
        .build()
        .writeTo(directory);
  }

  private void addClassJavaDoc() {
    factory.addJavadoc("<em>WARNING: GENERATED CODE</em>\n\n")
        .addJavadoc("A factory for caches optimized for a particular configuration.\n")
        .addJavadoc("\n@author ben.manes@gmail.com (Ben Manes)\n");
  }

  public static void main(String[] args) throws IOException {
    new LocalCacheFactoryGenerator(Paths.get(args[0])).generate();
  }
}
