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

import static com.github.benmanes.caffeine.cache.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.LoggingEvents.logEvents;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.platform.commons.support.AnnotationSupport.findAnnotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.slf4j.bridge.SLF4JBridgeHandler;

import com.github.benmanes.caffeine.cache.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.CacheSpec.ExecutorFailure;
import com.github.benmanes.caffeine.testing.LoggingEvents;
import com.github.benmanes.caffeine.testing.TrackingExecutor;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Var;

/**
 * A listener that validates the internal structure after a successful test execution.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheValidationListener implements
    BeforeTestExecutionCallback, InvocationInterceptor, AfterTestExecutionCallback {
  private static final Namespace NAMESPACE = Namespace.create(CacheValidationListener.class);

  @Override
  public void beforeTestExecution(ExtensionContext extension) {
    if (!SLF4JBridgeHandler.isInstalled()) {
      SLF4JBridgeHandler.removeHandlersForRootLogger();
      SLF4JBridgeHandler.install();
    }
    cleanUp();
  }

  @Override
  public <T> T interceptTestClassConstructor(Invocation<T> invocation,
      ReflectiveInvocationContext<Constructor<T>> invocationContext,
      ExtensionContext extensionContext) throws Throwable {
    return intercept(invocation, invocationContext, extensionContext);
  }

  @Override
  @SuppressWarnings("NullAway")
  public void interceptBeforeAllMethod(Invocation<@Nullable Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext) throws Throwable {
    intercept(invocation, invocationContext, extensionContext);
  }

  @Override
  @SuppressWarnings("NullAway")
  public void interceptBeforeEachMethod(Invocation<@Nullable Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext) throws Throwable {
    intercept(invocation, invocationContext, extensionContext);
  }

  @Override @SuppressWarnings("NullAway")
  public void interceptTestMethod(Invocation<@Nullable Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext) throws Throwable {
    intercept(invocation, invocationContext, extensionContext);
  }

  @Override
  public <T> T interceptTestFactoryMethod(Invocation<T> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext) throws Throwable {
    return intercept(invocation, invocationContext, extensionContext);
  }

  @Override @SuppressWarnings("NullAway")
  public void interceptTestTemplateMethod(Invocation<@Nullable Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext) throws Throwable {
    intercept(invocation, invocationContext, extensionContext);
  }

  @Override
  @SuppressWarnings("NullAway")
  public void interceptAfterEachMethod(Invocation<@Nullable Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext) throws Throwable {
    intercept(invocation, invocationContext, extensionContext);
  }

  @Override
  @SuppressWarnings("NullAway")
  public void interceptAfterAllMethod(Invocation<@Nullable Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext) throws Throwable {
    intercept(invocation, invocationContext, extensionContext);
  }

  @CanIgnoreReturnValue
  private static <T extends @Nullable Object> T intercept(Invocation<T> invocation,
      ReflectiveInvocationContext<?> invocationContext,
      ExtensionContext extensionContext) throws Throwable {
    cleanUp();
    captureContext(invocationContext, extensionContext);
    return invocation.proceed();
  }

  /** Extracts the {@link CacheContext} from the environment and stores it for validation. */
  private static void captureContext(ReflectiveInvocationContext<?> invocationContext,
      ExtensionContext extensionContext) {
    var key = StringUtils.substringBetween(extensionContext.getDisplayName(), "{", "}");
    if (key != null) {
      @Var var extension = Optional.of(extensionContext);
      do {
        var context = extension
            .map(ext -> ext.getStore(CacheProvider.NAMESPACE))
            .map(store -> store.remove(key, CacheContext.class));
        if (context.isPresent()) {
          extensionContext.getStore(NAMESPACE).put("cacheContext", context.orElseThrow());
          return;
        }
        extension = extension.flatMap(ExtensionContext::getParent);
      } while (extension.isPresent());
    }
    invocationContext.getArguments().stream()
        .filter(CacheContext.class::isInstance)
        .findAny().ifPresent(context ->
            extensionContext.getStore(NAMESPACE).put("cacheContext", context));
  }

  @Override
  public void afterTestExecution(ExtensionContext extension) {
    try {
      if (extension.getExecutionException().isEmpty()) {
        var context = extension.getStore(NAMESPACE).get("cacheContext", CacheContext.class);
        validate(extension, context);
      }
    } finally {
      cleanUp();
    }
  }

  /** Validates the internal state of the cache. */
  private static void validate(ExtensionContext extension, @Nullable CacheContext context) {
    if (context == null) {
      checkLogger(extension);
    } else {
      awaitExecutor(context);
      checkLogger(extension);
      checkNoStats(extension, context);
      checkExecutor(extension, context);
      checkNoEvictions(extension, context);
      assertThat(context.cache()).isValid();
    }
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
  private static void checkExecutor(ExtensionContext extension, CacheContext context) {
    var cacheSpec = annotation(extension, CacheSpec.class).orElseThrow();
    if (context.executorType() != CacheExecutor.DEFAULT) {
      if (cacheSpec.executorFailure() == ExecutorFailure.EXPECTED) {
        assertThat(context.executor().failed()).isGreaterThan(0);
      } else if (cacheSpec.executorFailure() == ExecutorFailure.DISALLOWED) {
        assertThat(context.executor().failed()).isEqualTo(0);
      }
    }
  }

  /** Checks the statistics if {@link CheckNoStats} is found. */
  private static void checkNoStats(ExtensionContext extension, CacheContext context) {
    if (annotation(extension, CheckNoStats.class).isPresent()) {
      assertThat(context).stats().hits(0).misses(0).success(0).failures(0);
    }
  }

  /** Checks the statistics if {@link CheckNoEvictions} is found. */
  private static void checkNoEvictions(ExtensionContext extension, CacheContext context) {
    if (annotation(extension, CheckNoEvictions.class).isPresent()) {
      assertThat(context).removalNotifications().hasNoEvictions();
      assertThat(context).evictionNotifications().isEmpty();
    }
  }

  /** Checks that no logs above the specified level were emitted. */
  private static void checkLogger(ExtensionContext extension) {
    annotation(extension, CheckMaxLogLevel.class).ifPresent(checkMaxLogLevel -> {
      assertWithMessage("maxLevel=%s", checkMaxLogLevel.value()).that(logEvents()
          .filter(event -> event.getLevel().toInt() > checkMaxLogLevel.value().toInt()))
          .isEmpty();
    });
  }

  /** Free memory by clearing unused resources after test execution. */
  private static void cleanUp() {
    CacheContext.interner().clear();
    LoggingEvents.cleanUp();
  }

  private static <T extends Annotation> Optional<T> annotation(
      ExtensionContext extension, Class<T> clazz) {
    return findAnnotation(extension.getTestMethod(), clazz)
        .or(() -> findAnnotation(extension.getTestClass(), clazz))
        .or(() -> extension.getEnclosingTestClasses().stream()
            .flatMap(enclosing -> findAnnotation(enclosing, clazz).stream())
            .findAny());
  }
}
