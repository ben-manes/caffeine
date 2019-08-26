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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.StringUtils;
import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestResult;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Policy.Eviction;
import com.github.benmanes.caffeine.cache.Policy.Expiration;
import com.github.benmanes.caffeine.cache.Policy.VarExpiration;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ExecutorFailure;

/**
 * A listener that validates the internal structure after a successful test execution.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheValidationListener implements IInvokedMethodListener {
  private static final Cache<Object, String> simpleNames = Caffeine.newBuilder().build();
  private static final AtomicBoolean detailedParams = new AtomicBoolean();
  private static final Object[] EMPTY_PARAMS = {};

  @Override
  public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {}

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
      testResult.setStatus(ITestResult.FAILURE);
      testResult.setThrowable(new AssertionError(getTestName(method), caught));
    } finally {
      cleanUp(testResult);
    }
  }

  /** Validates the internal state of the cache. */
  private void validate(ITestResult testResult) {
    boolean foundCache = false;
    CacheContext context = null;
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
      } else if (param instanceof CacheContext) {
        context = (CacheContext) param;
      }
    }
    if (context != null) {
      if (!foundCache) {
        assertThat(context.cache, is(validCache()));
      }
      checkWriter(testResult, context);
      checkNoStats(testResult, context);
      checkExecutor(testResult, context);
    }
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
      assertThat(executor.failureCount(), is(greaterThan(0)));
    } else if (cacheSpec.executorFailure() == ExecutorFailure.DISALLOWED) {
      assertThat(executor.failureCount(), is(0));
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
    boolean briefParams = !detailedParams.get();

    if (testResult.isSuccess() && briefParams) {
      testResult.setParameters(EMPTY_PARAMS);
      testResult.setThrowable(null);
    }

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

    /*
    // Enable in TestNG 7.0
    if ((testResult.getName() != null) && briefParams) {
      testResult.setTestName(simpleNames.get(testResult.getName(), Object::toString));
    }
    */

    CacheSpec.interner.get().clear();
  }
}
