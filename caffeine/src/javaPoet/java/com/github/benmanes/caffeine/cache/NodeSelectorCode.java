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

import com.squareup.javapoet.CodeBlock;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class NodeSelectorCode {
  private final CodeBlock.Builder block;

  private NodeSelectorCode() {
    block = CodeBlock.builder()
        .addStatement("$T sb = new $T()", StringBuilder.class, StringBuilder.class);
  }

  private NodeSelectorCode keys() {
    block.beginControlFlow("if (builder.isStrongKeys())")
            .addStatement("sb.append('S')")
        .nextControlFlow("else")
            .addStatement("sb.append('W')")
        .endControlFlow();
    return this;
  }

  private NodeSelectorCode values() {
    block.beginControlFlow("if (builder.isStrongValues())")
            .addStatement("sb.append(\"St\")")
        .nextControlFlow("else if (builder.isWeakValues())")
            .addStatement("sb.append('W')")
        .nextControlFlow("else")
            .addStatement("sb.append(\"So\")")
        .endControlFlow();
    return this;
  }

  private NodeSelectorCode expires() {
    block
        .beginControlFlow("if (builder.expiresVariable())")
            .beginControlFlow("if (builder.refreshes())")
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
        .beginControlFlow("if (builder.refreshes())")
            .addStatement("sb.append('R')")
        .endControlFlow();
    return this;
  }

  private NodeSelectorCode maximum() {
    block.beginControlFlow("if (builder.evicts())")
            .addStatement("sb.append('M')")
            .beginControlFlow("if ((isAsync && builder.evicts()) || builder.isWeighted())")
                .addStatement("sb.append('W')")
            .nextControlFlow("else")
                .addStatement("sb.append('S')")
            .endControlFlow()
        .endControlFlow();
    return this;
  }

  private CodeBlock build() {
    return block
        .addStatement("return valueOf(sb.toString())")
        .build();
  }

  public static CodeBlock get() {
    return new NodeSelectorCode()
        .keys()
        .values()
        .expires()
        .maximum()
        .build();
  }
}
