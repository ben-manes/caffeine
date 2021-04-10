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

import static com.github.benmanes.caffeine.cache.NodeFactoryGenerator.FACTORY;
import static com.github.benmanes.caffeine.cache.NodeFactoryGenerator.LOOKUP;
import static com.github.benmanes.caffeine.cache.Specifications.NODE_FACTORY;

import java.lang.invoke.MethodHandle;

import com.squareup.javapoet.CodeBlock;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class NodeSelectorCode {
  private final CodeBlock.Builder block;

  private NodeSelectorCode() {
    block = CodeBlock.builder()
        .addStatement("$1T sb = new $1T(\"$2N.\")",
            StringBuilder.class, NODE_FACTORY.rawType.packageName());
  }

  private NodeSelectorCode keys() {
    block.beginControlFlow("if (builder.isStrongKeys())")
            .addStatement("sb.append('P')")
        .nextControlFlow("else")
            .addStatement("sb.append('F')")
        .endControlFlow();
    return this;
  }

  private NodeSelectorCode values() {
    block.beginControlFlow("if (builder.isStrongValues())")
            .addStatement("sb.append('S')")
        .nextControlFlow("else if (builder.isWeakValues())")
            .addStatement("sb.append('W')")
        .nextControlFlow("else")
            .addStatement("sb.append('D')")
        .endControlFlow();
    return this;
  }

  private NodeSelectorCode expires() {
    block
        .beginControlFlow("if (builder.expiresVariable())")
            .beginControlFlow("if (builder.refreshAfterWrite())")
                .addStatement("sb.append('A')")
                .beginControlFlow("if (builder.evicts())")
                    .addStatement("sb.append('W')")
                .endControlFlow()
            .nextControlFlow("else")
                .addStatement("sb.append('W')")
            .endControlFlow()
        .nextControlFlow("else")
            .beginControlFlow("if (builder.expiresAfterAccess())")
                .addStatement("sb.append('A')")
            .endControlFlow()
            .beginControlFlow("if (builder.expiresAfterWrite())")
                .addStatement("sb.append('W')")
            .endControlFlow()
        .endControlFlow()
        .beginControlFlow("if (builder.refreshAfterWrite())")
            .addStatement("sb.append('R')")
        .endControlFlow();
    return this;
  }

  private NodeSelectorCode maximum() {
    block
        .beginControlFlow("if (builder.evicts())")
            .addStatement("sb.append('M')")
            .beginControlFlow("if (isAsync "
                + "|| (builder.isWeighted() && (builder.weigher != Weigher.singletonWeigher())))")
                .addStatement("sb.append('W')")
            .nextControlFlow("else")
                .addStatement("sb.append('S')")
            .endControlFlow()
        .endControlFlow();
    return this;
  }

  private NodeSelectorCode selector() {
    block
        .beginControlFlow("try")
            .addStatement("Class<?> clazz = Class.forName(sb.toString())")
            .addStatement("$T handle = $N.findConstructor(clazz, $N)",
                MethodHandle.class, LOOKUP, FACTORY)
            .addStatement("return ($T) handle.invoke()", NODE_FACTORY)
        .nextControlFlow("catch ($T | $T e)", RuntimeException.class, Error.class)
            .addStatement("throw e")
        .nextControlFlow("catch ($T t)", Throwable.class)
            .addStatement("throw new $T(sb.toString(), t)", IllegalStateException.class)
        .endControlFlow();
    return this;
  }

  private CodeBlock build() {
    return block.build();
  }

  public static CodeBlock get() {
    return new NodeSelectorCode()
        .keys()
        .values()
        .expires()
        .maximum()
        .selector()
        .build();
  }
}
