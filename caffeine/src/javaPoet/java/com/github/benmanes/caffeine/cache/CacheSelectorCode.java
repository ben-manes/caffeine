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
public final class CacheSelectorCode {
  private final CodeBlock.Builder name;

  private CacheSelectorCode() {
    name = CodeBlock.builder()
        .addStatement("$T sb = new $T()", StringBuilder.class, StringBuilder.class);
  }

  private CacheSelectorCode keys() {
    name.beginControlFlow("if (builder.isStrongKeys())")
            .addStatement("sb.append('S')")
        .nextControlFlow("else")
            .addStatement("sb.append('W')")
        .endControlFlow();
    return this;
  }

  private CacheSelectorCode values() {
    name.beginControlFlow("if (builder.isStrongValues())")
            .addStatement("sb.append('S')")
        .nextControlFlow("else")
            .addStatement("sb.append('I')")
        .endControlFlow();
    return this;
  }

  private CacheSelectorCode cacheLoader() {
    name.beginControlFlow("if (cacheLoader != null)")
            .addStatement("sb.append(\"Lo\")")
        .endControlFlow();
    return this;
  }

  private CacheSelectorCode removalListener() {
    name.beginControlFlow("if (builder.removalListener != null)")
            .addStatement("sb.append(\"Li\")")
        .endControlFlow();
    return this;
  }

  private CacheSelectorCode executor() {
    name.beginControlFlow("if (builder.executor != null)")
            .addStatement("sb.append('E')")
        .endControlFlow();
    return this;
  }

  private CacheSelectorCode stats() {
    name.beginControlFlow("if (builder.isRecordingStats())")
            .addStatement("sb.append('S')")
        .endControlFlow();
    return this;
  }

  private CacheSelectorCode maximum() {
    name.beginControlFlow("if (builder.evicts())")
            .addStatement("sb.append('M')")
            .beginControlFlow("if (builder.isWeighted())")
                .addStatement("sb.append('W')")
            .nextControlFlow("else")
                .addStatement("sb.append('S')")
            .endControlFlow()
        .endControlFlow();
    return this;
  }

  private CacheSelectorCode expires() {
    name.beginControlFlow("if (builder.expiresAfterAccess())")
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

  private CacheSelectorCode selector(Set<String> classNames) {
    CodeBlock.Builder switchBuilder = CodeBlock.builder();
    switchBuilder.beginControlFlow("switch (sb.toString())");
    for (String className : classNames) {
      switchBuilder.addStatement(
          "case $S: return new $N<>(builder, cacheLoader, async)", className, className);
    }
    switchBuilder.addStatement("default: throw new $T(sb.toString())", IllegalStateException.class);
    switchBuilder.endControlFlow();
    name.add(switchBuilder.build());
    return this;
  }

  private CodeBlock build() {
    return name.build();
  }

  public static CodeBlock get(Set<String> classNames) {
    return new CacheSelectorCode()
        .keys()
        .values()
        .cacheLoader()
        .removalListener()
        .executor()
        .stats()
        .maximum()
        .expires()
        .selector(classNames)
        .build();
  }
}
