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

import static com.github.benmanes.caffeine.cache.IsValidAsyncCache.validAsyncCache;
import static com.github.benmanes.caffeine.cache.IsValidCache.validCache;
import static com.github.benmanes.caffeine.cache.IsValidMapView.validAsMap;
import static com.github.benmanes.caffeine.cache.testing.CacheWriterVerifier.verifyWriter;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasHitCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasLoadFailureCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasLoadSuccessCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasMissCount;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.testng.ITestResult.FAILURE;

import java.lang.reflect.Method;
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
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Policy.Eviction;
import com.github.benmanes.caffeine.cache.Policy.Expiration;
import com.github.benmanes.caffeine.cache.Policy.VarExpiration;
import com.github.benmanes.caffeine.cache.Reset;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheScheduler;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ExecutorFailure;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Writer;

/**
 * A listener that validates the internal structure after a successful test execution.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheValidationListener implements ISuiteListener, IInvokedMethodListener {
  private static final Cache<Object, String> simpleNames = Caffeine.newBuilder().build();
  private static final ITestContext testngContext = Mockito.mock(ITestContext.class);
  private static final AtomicBoolean detailedParams = new AtomicBoolean();
  private static final Object[] EMPTY_PARAMS = {};

  private final List<Collection<?>> resultQueues = new CopyOnWriteArrayList<>();
  private final AtomicBoolean beforeCleanup = new AtomicBoolean();

  @Override
  public void onStart(ISuite suite) {
    if (suite instanceof SuiteRunner) {
      Reflect invokedMethods = Reflect.on(suite).fields().get("invokedMethods");
      if ((invokedMethods != null) && (invokedMethods.get() instanceof Collection)) {
        resultQueues.add(invokedMethods.get());
      }
    }
  }

  @Override
  public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {
    if (beforeCleanup.get() || !beforeCleanup.compareAndSet(false, true)) {
      return;
    }

    // Remove unused listener that retains all test results
    // https://github.com/cbeust/testng/issues/2096#issuecomment-706643074
    if (testResult.getTestContext() instanceof TestRunner) {
      TestRunner runner = (TestRunner) testResult.getTestContext();
      runner.getTestListeners().stream()
          .filter(listener -> listener.getClass() == TestListenerAdapter.class)
          .flatMap(listener -> Reflect.on(listener).fields().values().stream())
          .filter(field -> field.get() instanceof Collection)
          .forEach(field -> resultQueues.add(field.get()));

      resultQueues.add(runner.getFailedButWithinSuccessPercentageTests().getAllResults());
      resultQueues.add(runner.getSkippedTests().getAllResults());
      resultQueues.add(runner.getPassedTests().getAllResults());
      resultQueues.add(runner.getFailedTests().getAllResults());

      Reflect invokedMethods = Reflect.on(runner).fields().get("m_invokedMethods");
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
        assertThat(context.cache, is(validCache()));
      }
      checkWriter(testResult, context);
      checkNoStats(testResult, context);
      checkExecutor(testResult, context);
    }
  }

  /** Waits until the executor has completed all of the submitted work. */
  private void awaitExecutor(CacheContext context) {
    if ((context.cacheExecutor != CacheExecutor.DIRECT)
        && context.executor() instanceof TrackingExecutor) {
      TrackingExecutor executor = (TrackingExecutor) context.executor();
      if (executor.submitted() != executor.completed()) {
        await().pollInSameThread().until(() -> executor.submitted() == executor.completed());
      }
    }
  }

  /** Returns if the cache was found and, if so, then validates it. */
  private boolean findAndCheckCache(ITestResult testResult) {
    boolean foundCache = false;
    for (Object param : testResult.getParameters()) {
      if (param instanceof Cache<?, ?>) {
        foundCache = true;
        assertThat((Cache<?, ?>) param, is(validCache()));
      } else if (param instanceof AsyncLoadingCache<?, ?>) {
        foundCache = true;
        assertThat((AsyncLoadingCache<?, ?>) param, is(validAsyncCache()));
      } else if (param instanceof Map<?, ?>) {
        foundCache = true;
        assertThat((Map<?, ?>) param, is(validAsMap()));
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
    Method testMethod = testResult.getMethod().getConstructorOrMethod().getMethod();
    CacheSpec cacheSpec = testMethod.getAnnotation(CacheSpec.class);
    if (cacheSpec == null) {
      return;
    }

    assertThat("CacheContext required", context, is(not(nullValue())));
    if (!(context.executor() instanceof TrackingExecutor)) {
      return;
    }

    TrackingExecutor executor = (TrackingExecutor) context.executor();
    if (cacheSpec.executorFailure() == ExecutorFailure.EXPECTED) {
      assertThat(executor.failed(), is(greaterThan(0)));
    } else if (cacheSpec.executorFailure() == ExecutorFailure.DISALLOWED) {
      assertThat(executor.failed(), is(0));
    }
  }

  /** Checks the writer if {@link CheckNoWriter} is found. */
  private static void checkWriter(ITestResult testResult, CacheContext context) {
    Method testMethod = testResult.getMethod().getConstructorOrMethod().getMethod();
    CheckNoWriter checkWriter = testMethod.getAnnotation(CheckNoWriter.class);
    if (checkWriter == null) {
      return;
    }

    assertThat("Test requires CacheContext param for validation", context, is(not(nullValue())));
    verifyWriter(context, (verifier, writer) -> verifier.zeroInteractions());
  }

  /** Checks the statistics if {@link CheckNoStats} is found. */
  private static void checkNoStats(ITestResult testResult, CacheContext context) {
    Method testMethod = testResult.getMethod().getConstructorOrMethod().getMethod();
    boolean checkNoStats = testMethod.isAnnotationPresent(CheckNoStats.class);
    if (!checkNoStats) {
      return;
    }

    assertThat("Test requires CacheContext param for validation", context, is(not(nullValue())));
    assertThat(context, hasHitCount(0));
    assertThat(context, hasMissCount(0));
    assertThat(context, hasLoadSuccessCount(0));
    assertThat(context, hasLoadFailureCount(0));
  }

  /** Free memory by clearing unused resources after test execution. */
  private void cleanUp(ITestResult testResult) {
    resultQueues.forEach(Collection::clear);
    resetMocks(testResult);
    resetCache(testResult);

    boolean briefParams = !detailedParams.get();
    if (testResult.isSuccess() && briefParams) {
      clearTestResults(testResult);
    }

    stringifyParams(testResult, briefParams);
    dedupTestName(testResult, briefParams);
    CacheSpec.interner.get().clear();
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
        CacheContext context = (CacheContext) param;
        if (context.writer() == Writer.MOCKITO) {
          Mockito.clearInvocations(context.cacheWriter());
        }
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
        CacheContext context = (CacheContext) param;
        if (context.implementation() == Implementation.Caffeine) {
          Reset.destroy(context.cache());
        }
      }
    }
  }

  private void clearTestResults(ITestResult testResult) {
    TestResult result = (TestResult) testResult;
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
          || (param instanceof Expiration<?, ?>) || (param instanceof VarExpiration<?, ?>)
          || ((param instanceof CacheContext) && briefParams)) {
        params[i] = simpleNames.get(param.getClass(), key -> ((Class<?>) key).getSimpleName());
      } else if (param instanceof CacheContext) {
        params[i] = simpleNames.get(param.toString(), Object::toString);
      } else {
        params[i] = Objects.toString(param);
      }
    }
  }
}
