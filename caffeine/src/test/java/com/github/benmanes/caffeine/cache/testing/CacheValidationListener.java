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

import static com.github.benmanes.caffeine.cache.LocalCacheSubject.mapLocal;
import static com.github.benmanes.caffeine.cache.testing.AsyncCacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.google.common.truth.Truth.assertAbout;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.ITestResult.FAILURE;
import static uk.org.lidalia.slf4jext.ConventionalLevelHierarchy.OFF_LEVELS;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.StringUtils;
import org.joor.Reflect;
import org.mockito.Mockito;
import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ISuite;
import org.testng.ISuiteListener;
import org.testng.ITestContext;
import org.testng.ITestResult;
import org.testng.SuiteRunner;
import org.testng.TestListenerAdapter;
import org.testng.TestRunner;
import org.testng.internal.TestResult;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Policy.Eviction;
import com.github.benmanes.caffeine.cache.Policy.FixedExpiration;
import com.github.benmanes.caffeine.cache.Policy.VarExpiration;
import com.github.benmanes.caffeine.cache.Reset;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheScheduler;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ExecutorFailure;
import com.github.valfirst.slf4jtest.TestLoggerFactory;

/**
 * A listener that validates the internal structure after a successful test execution.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheValidationListener implements ISuiteListener, IInvokedMethodListener {
  private static final Cache<Object, String> simpleNames = Caffeine.newBuilder().build();
  private static final ITestContext testngContext = Mockito.mock(ITestContext.class);
  private static final AtomicBoolean detailedParams = new AtomicBoolean(false);
  private static final Object[] EMPTY_PARAMS = {};

  private final List<Collection<?>> resultQueues = new CopyOnWriteArrayList<>();
  private final AtomicBoolean beforeCleanup = new AtomicBoolean();

  @Override
  public void onStart(ISuite suite) {
    if (suite instanceof SuiteRunner) {
      var invokedMethods = Reflect.on(suite).fields().get("invokedMethods");
      if ((invokedMethods != null) && (invokedMethods.get() instanceof Collection)) {
        resultQueues.add(invokedMethods.get());
      }
    }
    disableLoggers();
  }

  @Override
  public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {
    TestLoggerFactory.getAllTestLoggers().values().stream()
        .forEach(logger -> logger.setEnabledLevels(OFF_LEVELS));
    TestLoggerFactory.clear();

    if (beforeCleanup.get() || !beforeCleanup.compareAndSet(false, true)) {
      return;
    }

    // Remove unused listener that retains all test results
    // https://github.com/cbeust/testng/issues/2096#issuecomment-706643074
    if (testResult.getTestContext() instanceof TestRunner) {
      var runner = (TestRunner) testResult.getTestContext();
      runner.getTestListeners().stream()
          .filter(listener -> listener.getClass() == TestListenerAdapter.class)
          .flatMap(listener -> Reflect.on(listener).fields().values().stream())
          .filter(field -> field.get() instanceof Collection)
          .forEach(field -> resultQueues.add(field.get()));

      resultQueues.add(runner.getFailedButWithinSuccessPercentageTests().getAllResults());
      resultQueues.add(runner.getSkippedTests().getAllResults());
      resultQueues.add(runner.getPassedTests().getAllResults());
      resultQueues.add(runner.getFailedTests().getAllResults());

      var invokedMethods = Reflect.on(runner).fields().get("m_invokedMethods");
      if ((invokedMethods != null) && (invokedMethods.get() instanceof Collection)) {
        resultQueues.add(invokedMethods.get());
      }
    }
  }

  @Override
  public void afterInvocation(IInvokedMethod method, ITestResult testResult) {
    try {
      if (testResult.isSuccess()) {
        validate(testResult);
      } else {
        if (!detailedParams.get()) {
          detailedParams.set(true);
        }
        testResult.setThrowable(new AssertionError(getTestName(method), testResult.getThrowable()));
      }
    } catch (Throwable caught) {
      testResult.setStatus(FAILURE);
      testResult.setThrowable(new AssertionError(getTestName(method), caught));
    } finally {
      cleanUp(testResult);
    }
  }

  /** Validates the internal state of the cache. */
  private void validate(ITestResult testResult) {
    CacheContext context = Arrays.stream(testResult.getParameters())
        .filter(param -> param instanceof CacheContext)
        .map(param -> (CacheContext) param)
        .findFirst().orElse(null);
    if (context != null) {
      awaitExecutor(context);
    }

    boolean foundCache = findAndCheckCache(testResult);
    if (context != null) {
      if (!foundCache) {
        if (context.cache != null) {
          assertThat(context.cache).isValid();
        } else if (context.asyncCache != null) {
          assertThat(context.asyncCache).isValid();
        }
      }
      checkNoStats(testResult, context);
      checkExecutor(testResult, context);
    }
  }

  /** Waits until the executor has completed all of the submitted work. */
  private void awaitExecutor(CacheContext context) {
    if (context.executor() instanceof TrackingExecutor) {
      var executor = (TrackingExecutor) context.executor();
      executor.resume();

      if ((context.cacheExecutor != CacheExecutor.DIRECT)
          && (context.cacheExecutor != CacheExecutor.DISCARDING)
          && (executor.submitted() != executor.completed())) {
        await().pollInSameThread().until(() -> executor.submitted() == executor.completed());
      }
    }
  }

  /** Returns if the cache was found and, if so, then validates it. */
  private boolean findAndCheckCache(ITestResult testResult) {
    boolean foundCache = false;
    for (Object param : testResult.getParameters()) {
      if (param instanceof Cache<?, ?>) {
        assertThat((Cache<?, ?>) param).isValid();
        foundCache = true;
      } else if (param instanceof AsyncCache<?, ?>) {
        assertThat((AsyncCache<?, ?>) param).isValid();
        foundCache = true;
      } else if (param instanceof Map<?, ?>) {
        assertAbout(mapLocal()).that((Map<?, ?>) param).isValid();
        foundCache = true;
      }
    }
    return foundCache;
  }

  /** Returns the name of the executed test. */
  private static String getTestName(IInvokedMethod method) {
    return StringUtils.substringAfterLast(method.getTestMethod().getTestClass().getName(), ".")
        + "#" + method.getTestMethod().getConstructorOrMethod().getName();
  }

  /** Checks whether the {@link TrackingExecutor} had unexpected failures. */
  private static void checkExecutor(ITestResult testResult, CacheContext context) {
    var testMethod = testResult.getMethod().getConstructorOrMethod().getMethod();
    var cacheSpec = testMethod.getAnnotation(CacheSpec.class);
    if (cacheSpec == null) {
      return;
    }

    assertWithMessage("CacheContext required").that(context).isNotNull();
    if (!(context.executor() instanceof TrackingExecutor)) {
      return;
    }

    var executor = (TrackingExecutor) context.executor();
    if (cacheSpec.executorFailure() == ExecutorFailure.EXPECTED) {
      assertThat(executor.failed()).isGreaterThan(0);
    } else if (cacheSpec.executorFailure() == ExecutorFailure.DISALLOWED) {
      assertThat(executor.failed()).isEqualTo(0);
    }
  }

  /** Checks the statistics if {@link CheckNoStats} is found. */
  private static void checkNoStats(ITestResult testResult, CacheContext context) {
    var testMethod = testResult.getMethod().getConstructorOrMethod().getMethod();
    boolean checkNoStats = testMethod.isAnnotationPresent(CheckNoStats.class);
    if (!checkNoStats) {
      return;
    }

    assertWithMessage("Test requires CacheContext param for validation").that(context).isNotNull();
    assertThat(context).stats().hits(0).misses(0).success(0).failures(0);
  }

  /** Free memory by clearing unused resources after test execution. */
  private void cleanUp(ITestResult testResult) {
    resultQueues.forEach(Collection::clear);
    TestLoggerFactory.clear();
    resetMocks(testResult);
    resetCache(testResult);

    boolean briefParams = !detailedParams.get();
    if (testResult.isSuccess() && briefParams) {
      clearTestResults(testResult);
    }

    stringifyParams(testResult, briefParams);
    dedupTestName(testResult, briefParams);
    CacheContext.interner().clear();
  }

  private void dedupTestName(ITestResult testResult, boolean briefParams) {
    if ((testResult.getName() != null) && briefParams) {
      testResult.setTestName(simpleNames.get(testResult.getName(), Object::toString));
    }
  }

  @SuppressWarnings("unchecked")
  private void resetMocks(ITestResult testResult) {
    for (Object param : testResult.getParameters()) {
      if (param instanceof CacheContext) {
        var context = (CacheContext) param;
        if (context.expiryType() == CacheExpiry.MOCKITO) {
          Mockito.clearInvocations(context.expiry());
        }
        if (context.cacheScheduler == CacheScheduler.MOCKITO) {
          Mockito.clearInvocations(context.scheduler());
        }
      }
    }
  }

  private void resetCache(ITestResult testResult) {
    for (Object param : testResult.getParameters()) {
      if (param instanceof CacheContext) {
        var context = (CacheContext) param;
        if (context.isCaffeine()) {
          Reset.destroy(context.cache());
        }
      }
    }
  }

  private void clearTestResults(ITestResult testResult) {
    var result = (TestResult) testResult;
    result.setParameters(EMPTY_PARAMS);
    result.setContext(testngContext);
    result.setThrowable(null);
  }

  private void stringifyParams(ITestResult testResult, boolean briefParams) {
    Object[] params = testResult.getParameters();
    for (int i = 0; i < params.length; i++) {
      Object param = params[i];
      if ((param instanceof AsyncCache<?, ?>) || (param instanceof Cache<?, ?>)
          || (param instanceof Map<?, ?>) || (param instanceof Eviction<?, ?>)
          || (param instanceof FixedExpiration<?, ?>) || (param instanceof VarExpiration<?, ?>)
          || ((param instanceof CacheContext) && briefParams)) {
        params[i] = simpleNames.get(param.getClass(), key -> ((Class<?>) key).getSimpleName());
      } else if (param instanceof CacheContext) {
        params[i] = simpleNames.get(param.toString(), Object::toString);
      } else {
        params[i] = Objects.toString(param);
      }
    }
  }

  private void disableLoggers() {
    String packageName = Caffeine.class.getPackageName();
    String[] classes = {"Caffeine", "LocalCache", "LocalManualCache", "LocalLoadingCache",
        "LocalAsyncCache", "BoundedLocalCache", "UnboundedLocalCache",
        "ExecutorServiceScheduler", "GuardedScheduler"};
    for (var className : classes) {
      System.getLogger(packageName + "." + className);
    }
    TestLoggerFactory.getAllTestLoggers().values().stream()
        .forEach(logger -> logger.setEnabledLevelsForAllThreads(OFF_LEVELS));
  }
}
