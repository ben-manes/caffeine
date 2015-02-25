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
  private final CodeBlock.Builder name;

  private NodeSelectorCode(int bufferSize) {
    name = CodeBlock.builder()
        .addStatement("$T sb = new $T($L)", StringBuilder.class, StringBuilder.class, bufferSize);
  }

  private NodeSelectorCode keys() {
    name.beginControlFlow("if (strongKeys)")
            .addStatement("sb.append(\"STRONG_KEYS\")")
        .nextControlFlow("else")
            .addStatement("sb.append(\"WEAK_KEYS\")")
        .endControlFlow();
    return this;
  }

  private NodeSelectorCode values() {
    name.beginControlFlow("if (strongValues)")
            .addStatement("sb.append(\"_STRONG_VALUES\")")
        .nextControlFlow("else if (weakValues)")
            .addStatement("sb.append(\"_WEAK_VALUES\")")
        .nextControlFlow("else")
            .addStatement("sb.append(\"_SOFT_VALUES\")")
        .endControlFlow();
    return this;
  }

  private NodeSelectorCode expires() {
    name.beginControlFlow("if (expiresAfterAccess)")
            .addStatement("sb.append(\"_EXPIRE_ACCESS\")")
        .endControlFlow()
        .beginControlFlow("if (expiresAfterWrite)")
            .addStatement("sb.append(\"_EXPIRE_WRITE\")")
        .endControlFlow()
        .beginControlFlow("if (refreshAfterWrite)")
            .addStatement("sb.append(\"_REFRESH_WRITE\")")
        .endControlFlow();
    return this;
  }

  private NodeSelectorCode maximum() {
    name.beginControlFlow("if (maximumSize)")
            .addStatement("sb.append(\"_MAXIMUM\")")
            .beginControlFlow("if (weighed)")
                .addStatement("sb.append(\"_WEIGHT\")")
            .nextControlFlow("else")
                .addStatement("sb.append(\"_SIZE\")")
            .endControlFlow()
        .endControlFlow();
    return this;
  }

  private CodeBlock build() {
    return name
        .addStatement("return valueOf(sb.toString())")
        .build();
  }

  public static CodeBlock get(int bufferSize) {
    return new NodeSelectorCode(bufferSize)
        .keys()
        .values()
        .expires()
        .maximum()
        .build();
  }
}
