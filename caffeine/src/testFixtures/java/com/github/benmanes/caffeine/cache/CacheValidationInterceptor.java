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

import static com.github.benmanes.caffeine.cache.AsyncCacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.CacheProvider.NAMESPACE;
import static com.github.benmanes.caffeine.cache.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.LoggingEvents.logEvents;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.platform.commons.support.AnnotationSupport.findAnnotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.slf4j.bridge.SLF4JBridgeHandler;

import com.github.benmanes.caffeine.cache.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.CacheSpec.ExecutorFailure;
import com.github.benmanes.caffeine.testing.LoggingEvents;
import com.github.benmanes.caffeine.testing.TrackingExecutor;
import com.google.errorprone.annotations.Var;

/**
 * A listener that validates the internal structure after a successful test execution.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheValidationInterceptor implements BeforeAllCallback, InvocationInterceptor {

  @Override
  public void beforeAll(ExtensionContext context) {
    if (!SLF4JBridgeHandler.isInstalled()) {
      SLF4JBridgeHandler.removeHandlersForRootLogger();
      SLF4JBridgeHandler.install();
    }
  }

  @Override @SuppressWarnings("NullAway")
  public void interceptTestMethod(Invocation<@Nullable Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext) throws Throwable {
    intercept(invocation, invocationContext, extensionContext);
  }

  @Override @SuppressWarnings("NullAway")
  public void interceptTestTemplateMethod(Invocation<@Nullable Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext) throws Throwable {
    intercept(invocation, invocationContext, extensionContext);
  }

  private static void intercept(Invocation<?> invocation,
      ReflectiveInvocationContext<?> invocationContext,
      ExtensionContext extensionContext) throws Throwable {
    try {
      invocation.proceed();
      validate(invocationContext, extensionContext);
    } finally {
      cleanUp(extensionContext);
    }
  }

  /** Validates the internal state of the cache. */
  private static void validate(ReflectiveInvocationContext<?> invocationContext,
      ExtensionContext extensionContext) {
    var cacheContext = findCacheContext(invocationContext, extensionContext);
    cacheContext.ifPresent(context -> {
      awaitExecutor(context);
      checkNoStats(extensionContext, context);
      checkExecutor(extensionContext, context);
      checkNoEvictions(extensionContext, context);
    });
    checkLogger(extensionContext);
    checkCache(invocationContext, cacheContext);
  }

  /** Returns the {@link CacheContext} from the environment. */
  private static Optional<CacheContext> findCacheContext(
      ReflectiveInvocationContext<?> invocationContext, ExtensionContext extensionContext) {
    for (var argument : invocationContext.getArguments()) {
      if (argument instanceof CacheContext) {
        return Optional.of((CacheContext) argument);
      }
    }
    var key = CacheProvider.getStoreKey(extensionContext.getDisplayName());
    return (key == null)
        ? Optional.empty()
        : Optional.ofNullable(extensionContext.getStore(NAMESPACE).get(key, CacheContext.class));
  }

  /** Returns the {@link CacheContext} from the environment. */
  private static void checkCache(
      ReflectiveInvocationContext<?> invocationContext, Optional<CacheContext> cacheContext) {
    for (var argument : invocationContext.getArguments()) {
      if (argument instanceof Cache<?, ?>) {
        assertThat((Cache<?, ?>) argument).isValid();
        return;
      } else if (argument instanceof AsyncCache<?, ?>) {
        assertThat((AsyncCache<?, ?>) argument).isValid();
        return;
      }
    }
    cacheContext.map(CacheContext::cache)
        .ifPresent(cache -> assertThat(cache).isValid());
  }

  /** Waits until the executor has completed all of the submitted work. */
  @SuppressWarnings("resource")
  private static void awaitExecutor(CacheContext context) {
    if (context.executorType() != CacheExecutor.DEFAULT) {
      context.executor().resume();
      if ((context.executorType() != CacheExecutor.DIRECT)
          && (context.executorType() != CacheExecutor.DISCARDING)
          && (context.executor().submitted() != context.executor().completed())) {
        await().pollInSameThread().until(() ->
            context.executor().submitted() == context.executor().completed());
      }
    }
  }

  /** Checks whether the {@link TrackingExecutor} had unexpected failures. */
  @SuppressWarnings("resource")
  private static void checkExecutor(ExtensionContext extensionContext, CacheContext context) {
    var cacheSpec = annotation(extensionContext, CacheSpec.class).orElseThrow();
    if (context.executorType() != CacheExecutor.DEFAULT) {
      if (cacheSpec.executorFailure() == ExecutorFailure.EXPECTED) {
        assertThat(context.executor().failed()).isGreaterThan(0);
      } else if (cacheSpec.executorFailure() == ExecutorFailure.DISALLOWED) {
        assertThat(context.executor().failed()).isEqualTo(0);
      }
    }
  }

  /** Checks the statistics if {@link CheckNoStats} is found. */
  private static void checkNoStats(ExtensionContext extensionContext, CacheContext context) {
    if (annotation(extensionContext, CheckNoStats.class).isPresent()) {
      assertThat(context).stats().hits(0).misses(0).success(0).failures(0);
    }
  }

  /** Checks the statistics if {@link CheckNoEvictions} is found. */
  private static void checkNoEvictions(ExtensionContext extensionContext, CacheContext context) {
    if (annotation(extensionContext, CheckNoEvictions.class).isPresent()) {
      assertThat(context).removalNotifications().hasNoEvictions();
      assertThat(context).evictionNotifications().isEmpty();
    }
  }

  /** Checks that no logs above the specified level were emitted. */
  private static void checkLogger(ExtensionContext extensionContext) {
    annotation(extensionContext, CheckMaxLogLevel.class).ifPresent(checkMaxLogLevel -> {
      assertWithMessage("maxLevel=%s", checkMaxLogLevel.value()).that(logEvents()
          .filter(event -> event.getLevel().toInt() > checkMaxLogLevel.value().toInt()))
          .isEmpty();
    });
  }

  /** Free memory by clearing unused resources after test execution. */
  private static void cleanUp(ExtensionContext extensionContext) {
    removeCacheContextFromStore(extensionContext);
    CacheContext.interner().clear();
    LoggingEvents.cleanUp();
  }

  /** Removes the {@link CacheContext} from the environment. */
  private static void removeCacheContextFromStore(ExtensionContext extensionContext) {
    var key = CacheProvider.getStoreKey(extensionContext.getDisplayName());
    if (key == null) {
      return;
    }
    @Var var extension = extensionContext;
    for (;;) {
      var cacheContext = extension.getStore(NAMESPACE).remove(key, CacheContext.class);
      if (cacheContext != null) {
        return;
      }
      var parent = extension.getParent();
      if (parent.isEmpty()) {
        return;
      }
      extension = parent.orElseThrow();
    }
  }

  private static <T extends Annotation> Optional<T> annotation(
      ExtensionContext extensionContext, Class<T> clazz) {
    return findAnnotation(extensionContext.getTestMethod(), clazz)
        .or(() -> findAnnotation(extensionContext.getTestClass(), clazz))
        .or(() -> extensionContext.getEnclosingTestClasses().stream()
            .flatMap(enclosing -> findAnnotation(enclosing, clazz).stream())
            .findAny());
  }
}
