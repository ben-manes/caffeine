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

import java.util.Set;

import com.squareup.javapoet.CodeBlock;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LocalCacheSelectorCode {
  private final CodeBlock.Builder block;

  private LocalCacheSelectorCode() {
    block = CodeBlock.builder()
        .addStatement("$T sb = new $T()", StringBuilder.class, StringBuilder.class);
  }

  private LocalCacheSelectorCode keys() {
    block.beginControlFlow("if (builder.isStrongKeys())")
            .addStatement("sb.append('S')")
        .nextControlFlow("else")
            .addStatement("sb.append('W')")
        .endControlFlow();
    return this;
  }

  private LocalCacheSelectorCode values() {
    block.beginControlFlow("if (builder.isStrongValues())")
            .addStatement("sb.append('S')")
        .nextControlFlow("else")
            .addStatement("sb.append('I')")
        .endControlFlow();
    return this;
  }

  private LocalCacheSelectorCode removalListener() {
    block.beginControlFlow("if (builder.removalListener != null)")
            .addStatement("sb.append(\"Li\")")
        .endControlFlow();
    return this;
  }

  private LocalCacheSelectorCode stats() {
    block.beginControlFlow("if (builder.isRecordingStats())")
            .addStatement("sb.append('S')")
        .endControlFlow();
    return this;
  }

  private LocalCacheSelectorCode maximum() {
    block.beginControlFlow("if (builder.evicts())")
            .addStatement("sb.append('M')")
            .beginControlFlow("if (builder.isWeighted())")
                .addStatement("sb.append('W')")
            .nextControlFlow("else")
                .addStatement("sb.append('S')")
            .endControlFlow()
        .endControlFlow();
    return this;
  }

  private LocalCacheSelectorCode expires() {
    block.beginControlFlow("if (builder.expiresAfterAccess() || builder.expiresVariable())")
            .addStatement("sb.append('A')")
        .endControlFlow()
        .beginControlFlow("if (builder.expiresAfterWrite())")
            .addStatement("sb.append('W')")
        .endControlFlow()
        .beginControlFlow("if (builder.refreshes())")
            .addStatement("sb.append('R')")
        .endControlFlow();
    return this;
  }

  private LocalCacheSelectorCode selector(Set<String> classNames) {
    CodeBlock.Builder switchBuilder = CodeBlock.builder();
    switchBuilder.beginControlFlow("switch (sb.toString())");
    for (String className : classNames) {
      switchBuilder.addStatement(
          "case $S: return new $N<>(builder, cacheLoader, async)", className, className);
    }
    switchBuilder.addStatement("default: throw new $T(sb.toString())", IllegalStateException.class);
    switchBuilder.endControlFlow();
    block.add(switchBuilder.build());
    return this;
  }

  private CodeBlock build() {
    return block.build();
  }

  public static CodeBlock get(Set<String> classNames) {
    return new LocalCacheSelectorCode()
        .keys()
        .values()
        .removalListener()
        .stats()
        .maximum()
        .expires()
        .selector(classNames)
        .build();
  }
}
