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
package com.github.benmanes.playground;

import java.io.IOException;
import java.util.Arrays;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;

/**
 * Experiments with JavaPoet to generate the 96 different cache entry types. If successful, this
 * code will be moved into its own sourceSet for build time generation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class NodeGenerator {
  enum Strength { STRONG, WEAK, SOFT }

  public void generate() throws IOException {
    TypeSpec.Builder nodeFactoryBuilder = TypeSpec.enumBuilder("NodeFactory")
        .addJavadoc("<em>WARNING: GENERATED CODE</em>\n\n")
        .addJavadoc("A factory for cache nodes optimized for a particular configuration.\n")
        .addJavadoc("\n@author ben.manes@gmail.com (Ben Manes)\n")
        .addMethod(newNode() /* .addModifiers(Modifier.ABSTRACT) */.build());
    for (Strength keyStrength : Arrays.asList(Strength.STRONG, Strength.WEAK)) {
      String name = keyStrength.name();
      TypeSpec node = nodeSpec(keyStrength);
      nodeFactoryBuilder.addEnumConstant(name, node);
    }
    JavaFile javaFile = JavaFile.builder(
        getClass().getPackage().getName(), nodeFactoryBuilder.build())
        .addFileComment("Copyright 2015 Ben Manes. All Rights Reserved.")
        .build();
    javaFile.emit(System.out);
  }

  public TypeSpec nodeSpec(Strength keyStrength) {
    MethodSpec newNodeSpec = newNode()
        .build();
    return TypeSpec.anonymousClassBuilder("")
        .addMethod(newNodeSpec)
        .build();
  }

  private MethodSpec.Builder newNode() {
    return MethodSpec.methodBuilder("newNode").returns(Node.class);
  }

  public static void main(String[] args) throws IOException {
    new NodeGenerator().generate();
  }

  interface Node {}
}
