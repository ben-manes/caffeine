package com.github.benmanes.caffeine.cache.node;

import com.palantir.javapoet.TypeName;

public interface KeyContext {
  boolean isStrongKeys();
  TypeName keyReferenceType();
  void addVarHandle(String name, TypeName type);
}