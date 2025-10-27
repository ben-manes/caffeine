/*
 * Copyright 2025 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.guava.module;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Requires;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.guava.CaffeinatedGuava;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;

/**
 * The test cases for the Java Platform Module System.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class ModuleTest {

  @Test
  void sanity() {
    var loader = new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) {
        return -key;
      }
    };
    LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(Caffeine.newBuilder(), loader);
    assertEquals(-1, cache.getUnchecked(1).intValue());
  }

  @Test
  void descriptor_name() {
    var descriptor = getModuleDescriptor();
    assertEquals("com.github.benmanes.caffeine.guava", descriptor.name());
  }

  @Test
  void descriptor_exports() {
    var descriptor = getModuleDescriptor();
    var exports = descriptor.exports().stream()
        .map(Exports::source)
        .collect(toImmutableSet());
    assertEquals(ImmutableSet.of("com.github.benmanes.caffeine.guava"), exports);
  }

  @Test
  void descriptor_noQualifiedExports() {
    var descriptor = getModuleDescriptor();
    var qualifiedExports = descriptor.exports().stream()
        .filter(export -> !export.targets().isEmpty())
        .collect(toImmutableSet());
    assertTrue(qualifiedExports.isEmpty(), () ->
        "Should not have qualified exports, but found: " + qualifiedExports);
  }

  @Test
  void descriptor_requires() {
    var descriptor = getModuleDescriptor();
    var requires = descriptor.requires().stream()
        .map(Requires::name)
        .collect(toImmutableSet());
    assertTrue(requires.contains("java.base"), "Should require java.base module");
  }

  @Test
  void descriptor_requires_static() {
    var descriptor = getModuleDescriptor();
    var staticRequires = descriptor.requires().stream()
        .filter(req -> req.modifiers().contains(Requires.Modifier.STATIC))
        .map(Requires::name)
        .collect(toImmutableSet());
    assertTrue(staticRequires.contains("com.google.errorprone.annotations"),
        "Should have static require for errorprone annotations");
    assertTrue(staticRequires.contains("org.jspecify"),
        "Should have static require for jspecify");
    assertEquals(2, staticRequires.size());
  }

  @Test
  void descriptor_requires_transitive() {
    var descriptor = getModuleDescriptor();
    var transitiveRequires = descriptor.requires().stream()
        .filter(req -> req.modifiers().contains(Requires.Modifier.TRANSITIVE))
        .map(Requires::name)
        .collect(toImmutableSet());
    // Guava adapter should transitively require both caffeine and guava
    assertTrue(transitiveRequires.contains("com.github.benmanes.caffeine"),
        () -> "Should have transitive require for caffeine: " + transitiveRequires);
    assertTrue(transitiveRequires.contains("com.google.common"),
        () -> "Should have transitive require for guava: " + transitiveRequires);
  }

  @Test
  void descriptor_opens() {
    var descriptor = getModuleDescriptor();
    assertTrue(descriptor.opens().isEmpty(), "Should not open any packages");
  }

  @Test
  void descriptor_provides() {
    var descriptor = getModuleDescriptor();
    assertTrue(descriptor.provides().isEmpty(), "Should not provide any services");
  }

  @Test
  void descriptor_uses() {
    var descriptor = getModuleDescriptor();
    assertTrue(descriptor.uses().isEmpty(), "Should not use any services");
  }

  @Test
  void descriptor_automatic() {
    var descriptor = getModuleDescriptor();
    assertFalse(descriptor.isAutomatic(), "Should not be an automatic module");
  }

  @Test
  void descriptor_open() {
    var descriptor = getModuleDescriptor();
    assertFalse(descriptor.isOpen(), "Should not be an open module");
  }

  @Test
  void descriptor_packages() {
    var targetModule = CaffeinatedGuava.class.getModule();
    var descriptor = getModuleDescriptor();
    var packages = descriptor.packages();
    var exportedPackages = descriptor.exports().stream()
        .map(Exports::source)
        .collect(toImmutableSet());
    assertEquals(exportedPackages, packages);
    assertEquals(exportedPackages, targetModule.getPackages());
  }

  @Test
  void module_named() {
    var targetModule = CaffeinatedGuava.class.getModule();
    assertTrue(targetModule.isNamed(), "Module should be named");
    assertEquals("com.github.benmanes.caffeine.guava", targetModule.getName());
  }

  @Test
  void module_readability() {
    var testModule = getClass().getModule();
    var guavaModule = CaffeinatedGuava.class.getModule();
    assertTrue(testModule.isNamed(), "Test module should be named");
    assertEquals("com.github.benmanes.caffeine.guava.module", testModule.getName());
    assertTrue(testModule.canRead(guavaModule),
        "Test module should be able to read the guava module");
  }

  private static ModuleDescriptor getModuleDescriptor() {
    var module = CaffeinatedGuava.class.getModule();
    return module.getDescriptor();
  }
}
