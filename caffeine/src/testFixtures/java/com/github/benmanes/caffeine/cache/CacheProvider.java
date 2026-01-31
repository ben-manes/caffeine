/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.junit.platform.commons.support.AnnotationSupport.findAnnotation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.support.ParameterDeclaration;
import org.junit.jupiter.params.support.ParameterDeclarations;

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Var;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * A data provider that generates caches based on the test method's parameters and the
 * {@link CacheSpec} configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheProvider implements ArgumentsProvider {
  private static final ImmutableSet<Class<?>> GUAVA_INCOMPATIBLE = ImmutableSet.of(
      AsyncCache.class, AsyncLoadingCache.class, BoundedLocalCache.class, Policy.Eviction.class,
      Policy.FixedExpiration.class, Policy.VarExpiration.class, Policy.FixedRefresh.class);
  public static final Namespace NAMESPACE = Namespace.create(CacheProvider.class);

  /** Returns the lazily generated test parameters. */
  @Override public Stream<? extends Arguments> provideArguments(
      ParameterDeclarations parameters, ExtensionContext extension) {
    var cacheSpec = findAnnotation(parameters.getSourceElement(), CacheSpec.class)
        .or(() -> extension.getTestClass().flatMap(test -> findAnnotation(test, CacheSpec.class)))
        .orElseThrow(() -> new AssertionError("@CacheSpec not found"));
    var params = parameters.getAll();
    return scenarios(params, cacheSpec)
        .map(context -> asTestCases(params, extension, context))
        .filter(Objects::nonNull);
  }

  /** Returns the test scenarios. */
  private static Stream<CacheContext> scenarios(
      Collection<ParameterDeclaration> parameters, CacheSpec cacheSpec) {
    return CacheGenerator.forCacheSpec(cacheSpec)
        .isGuavaCompatible(isGuavaCompatible(parameters))
        .isLoadingOnly(isLoadingOnly(parameters))
        .isAsyncOnly(isAsyncOnly(parameters))
        .generate();
  }

  /**
   * Returns the test case parameters based on the method parameter types or an empty array if
   * incompatible.
   */
  @SuppressFBWarnings("UCC_UNRELATED_COLLECTION_CONTENTS")
  private static @Nullable Arguments asTestCases(List<ParameterDeclaration> parameters,
      ExtensionContext extension, CacheContext context) {
    @Var boolean store = true;
    CacheGenerator.initialize(context);
    var params = new Object[parameters.size()];
    for (int i = 0; i < params.length; i++) {
      var parameter = parameters.get(i);
      var clazz = parameter.getParameterType();
      if (clazz.isInstance(context)) {
        params[i] = context;
        store = false;
      } else if (clazz.isInstance(context.cache())) {
        params[i] = context.cache();
      } else if (context.isAsync() && clazz.isInstance(context.asyncCache())) {
        params[i] = context.asyncCache();
      } else if (clazz.isAssignableFrom(Map.class)) {
        params[i] = context.cache().asMap();
      } else if (clazz.isAssignableFrom(BoundedLocalCache.class)) {
        if (!(context.cache().asMap() instanceof BoundedLocalCache)) {
          return null;
        }
        params[i] = context.cache().asMap();
      } else if (clazz.isAssignableFrom(Policy.Eviction.class)) {
        params[i] = context.cache().policy().eviction().orElseThrow();
      } else if (clazz.isAssignableFrom(Policy.VarExpiration.class)) {
        params[i] = context.cache().policy().expireVariably().orElseThrow();
      } else if (clazz.isAssignableFrom(Policy.FixedRefresh.class)) {
        params[i] = context.cache().policy().refreshAfterWrite().orElseThrow();
      } else if (clazz.isAssignableFrom(Policy.FixedExpiration.class)) {
        if (parameter.getAnnotatedElement().isAnnotationPresent(ExpireAfterAccess.class)) {
          params[i] = context.cache().policy().expireAfterAccess().orElseThrow();
        } else if (parameter.getAnnotatedElement().isAnnotationPresent(ExpireAfterWrite.class)) {
          params[i] = context.cache().policy().expireAfterWrite().orElseThrow();
        } else {
          throw new AssertionError("FixedExpiration must have a qualifier annotation");
        }
      }
      checkNotNull(params[i], "Unknown parameter type: %s", clazz);
    }
    var displayName = context.toString();
    if (store) {
      // If the cache context is not used as an argument then store it for the validation listener
      // to recover. This allows for both performing an integrity check as well as ensuring that
      // the cache entries are not collected prematurely due to weak/soft reference collection.
      var key = StringUtils.substringBetween(displayName, "{", "}");
      extension.getStore(NAMESPACE).put(key, context);
    }
    return argumentSet(displayName, params);
  }

  /** Returns if the test parameters requires an asynchronous cache. */
  private static boolean isAsyncOnly(Collection<ParameterDeclaration> parameters) {
    return hasParameterOfType(AsyncCache.class, parameters);
  }

  /** Returns if the test parameters requires a loading cache. */
  private static boolean isLoadingOnly(Collection<ParameterDeclaration> parameters) {
    return hasParameterOfType(AsyncLoadingCache.class, parameters)
        || hasParameterOfType(LoadingCache.class, parameters);
  }

  /** Returns if the required cache is compatible with the Guava test adapters. */
  private static boolean isGuavaCompatible(Collection<ParameterDeclaration> parameters) {
    return parameters.stream()
        .noneMatch(parameter -> GUAVA_INCOMPATIBLE.contains(parameter.getParameterType()));
  }

  /** Returns if the class matches a parameter type. */
  private static boolean hasParameterOfType(Class<?> clazz,
      Collection<ParameterDeclaration> parameters) {
    return parameters.stream()
        .map(ParameterDeclaration::getParameterType)
        .anyMatch(clazz::isAssignableFrom);
  }
}
