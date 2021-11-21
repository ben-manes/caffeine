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
package com.github.benmanes.caffeine.cache.testing;

import static com.google.common.base.Preconditions.checkNotNull;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;

import org.testng.annotations.DataProvider;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Policy;
import com.google.common.collect.ImmutableSet;

/**
 * A data provider that generates caches based on the test method's parameters and the
 * {@link CacheSpec} configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheProvider {
  private static final Class<?> BOUNDED_LOCAL_CACHE =
      classForName("com.github.benmanes.caffeine.cache.BoundedLocalCache");
  private static final ImmutableSet<Class<?>> GUAVA_INCOMPATIBLE = ImmutableSet.of(
      AsyncCache.class, AsyncLoadingCache.class, BOUNDED_LOCAL_CACHE, Policy.Eviction.class,
      Policy.FixedExpiration.class, Policy.VarExpiration.class, Policy.FixedRefresh.class);

  private final Parameter[] parameters;
  private final Method testMethod;

  private CacheProvider(Method testMethod) {
    this.parameters = testMethod.getParameters();
    this.testMethod = testMethod;
  }

  /** Returns the lazily generated test parameters. */
  @DataProvider(name = "caches")
  public static Iterator<Object[]> providesCaches(Method testMethod) {
    return new CacheProvider(testMethod).getTestCases();
  }

  /** Returns the parameters for the test case scenarios. */
  private Iterator<Object[]> getTestCases() {
    return scenarios()
        .map(this::asTestCases)
        .filter(params -> params.length > 0)
        .iterator();
  }

  /** Returns the test scenarios. */
  private Stream<CacheContext> scenarios() {
    var cacheSpec = checkNotNull(testMethod.getAnnotation(CacheSpec.class), "@CacheSpec not found");
    var generator = new CacheGenerator(cacheSpec, Options.fromSystemProperties(),
        isLoadingOnly(), isAsyncOnly(), isGuavaCompatible());
    return generator.generate();
  }

  /**
   * Returns the test case parameters based on the method parameter types or an empty array if
   * incompatible.
   */
  private Object[] asTestCases(CacheContext context) {
    boolean intern = true;
    CacheGenerator.initialize(context);
    var params = new Object[parameters.length];
    for (int i = 0; i < parameters.length; i++) {
      Class<?> clazz = parameters[i].getType();
      if (clazz.isInstance(context)) {
        params[i] = context;
        intern = false;
      } else if (clazz.isInstance(context.cache())) {
        params[i] = context.cache();
      } else if (clazz.isInstance(context.asyncCache)) {
        params[i] = context.asyncCache;
      } else if (clazz.isAssignableFrom(Map.class)) {
        params[i] = context.cache().asMap();
      } else if (clazz.isAssignableFrom(BOUNDED_LOCAL_CACHE)) {
        if (!BOUNDED_LOCAL_CACHE.isInstance(context.cache().asMap())) {
          return new Object[] {};
        }
        params[i] = context.cache().asMap();
      } else if (clazz.isAssignableFrom(Policy.Eviction.class)) {
        params[i] = context.cache().policy().eviction().orElse(null);
      } else if (clazz.isAssignableFrom(Policy.VarExpiration.class)) {
        params[i] = context.cache().policy().expireVariably().orElseThrow();
      } else if (clazz.isAssignableFrom(Policy.FixedRefresh.class)) {
        params[i] = context.cache().policy().refreshAfterWrite().orElseThrow();
      } else if (clazz.isAssignableFrom(Policy.FixedExpiration.class)) {
        if (parameters[i].isAnnotationPresent(ExpireAfterAccess.class)) {
          params[i] = context.cache().policy().expireAfterAccess().orElseThrow();
        } else if (parameters[i].isAnnotationPresent(ExpireAfterWrite.class)) {
          params[i] = context.cache().policy().expireAfterWrite().orElseThrow();
        } else {
          throw new AssertionError("FixedExpiration must have a qualifier annotation");
        }
      }
      if (params[i] == null) {
        checkNotNull(params[i], "Unknown parameter type: %s", clazz);
      }
    }
    if (intern) {
      // Retain a strong reference to the context throughout the test execution so that the
      // cache entries are not collected due to the test not accepting the context parameter
      CacheContext.intern(context);
    }
    return params;
  }

  /** Returns if the test parameters requires an asynchronous cache. */
  private boolean isAsyncOnly() {
    return hasParameterOfType(AsyncCache.class);
  }

  /** Returns if the test parameters requires a loading cache. */
  private boolean isLoadingOnly() {
    return hasParameterOfType(AsyncLoadingCache.class) || hasParameterOfType(LoadingCache.class);
  }

  /** Returns if the required cache is compatible with the Guava test adapters. */
  private boolean isGuavaCompatible() {
    return Arrays.stream(parameters)
        .noneMatch(parameter -> GUAVA_INCOMPATIBLE.contains(parameter.getType()));
  }

  /** Returns if the class matches a parameter type. */
  private boolean hasParameterOfType(Class<?> clazz) {
    return Arrays.stream(parameters).map(Parameter::getType).anyMatch(clazz::isAssignableFrom);
  }

  private static Class<?> classForName(String className) {
    try {
      return Class.forName(className);
    } catch (ClassNotFoundException e) {
      throw new AssertionError(e);
    }
  }
}
