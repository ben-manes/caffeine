package com.github.benmanes.caffeine.cache.node;

import com.palantir.javapoet.TypeName;

public interface ValueContext {
  boolean isStrongValues();
  TypeName valueReferenceType();
}