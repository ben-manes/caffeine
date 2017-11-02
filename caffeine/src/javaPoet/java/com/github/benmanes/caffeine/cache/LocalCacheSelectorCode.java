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

import java.lang.reflect.Constructor;
import java.util.Set;

import com.squareup.javapoet.CodeBlock;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
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

  private LocalCacheSelectorCode selector() {
    CodeBlock.Builder reflectBuilder = CodeBlock.builder();
    reflectBuilder.add("try {\n"
            + "  Class<?> cls = LocalCacheFactory.class.getClassLoader()\n"
            + "    .loadClass(\"com.github.benmanes.caffeine.cache.LocalCacheFactory$$\" + sb.toString());\n"
            + "  $T<?> ctor = cls.getDeclaredConstructor(Caffeine.class, CacheLoader.class, boolean.class);\n"
            + "  return (BoundedLocalCache<K, V>) ctor.newInstance(builder, cacheLoader, async);\n"
            + "} catch (ReflectiveOperationException e) {\n"
            + "  throw new IllegalStateException(sb.toString());\n"
            + "}\n"
            + "\n", Constructor.class);
    block.add(reflectBuilder.build());
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
        .selector()
        .build();
  }
}
