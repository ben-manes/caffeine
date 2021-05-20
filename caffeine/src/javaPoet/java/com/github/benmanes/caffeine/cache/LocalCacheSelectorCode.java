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

import static com.github.benmanes.caffeine.cache.Specifications.LOCAL_CACHE_FACTORY;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class LocalCacheSelectorCode {
  private final CodeBlock.Builder block;

  private LocalCacheSelectorCode() {
    block = CodeBlock.builder()
        .addStatement("$1T sb = new $1T()", StringBuilder.class);
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
            .addStatement("sb.append('L')")
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
        .beginControlFlow("if (builder.refreshAfterWrite())")
            .addStatement("sb.append('R')")
        .endControlFlow();
    return this;
  }

  private LocalCacheSelectorCode selector(Iterable<String> simpleNames) {
    block
            .addStatement("String name = sb.toString()")
            .beginControlFlow("switch (name)");
    String packageName = LOCAL_CACHE_FACTORY.packageName();
    for (String name : simpleNames) {
      block.add("case $S:", name)
              .addStatement("return new $T<>(builder, cacheLoader, async)", ClassName.get(packageName, name));
    }
    block.add("default:")
            .addStatement("throw new $T($S + name)", IllegalStateException.class, "Unknown type: ")
            .endControlFlow();
    return this;
  }

  private CodeBlock build() {
    return block.build();
  }

  public static CodeBlock get(Iterable<String> simpleNames) {
    return new LocalCacheSelectorCode()
        .keys()
        .values()
        .removalListener()
        .stats()
        .maximum()
        .expires()
        .selector(simpleNames)
        .build();
  }
}
