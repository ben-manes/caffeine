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
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasHitCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasLoadFailureCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasLoadSuccessCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasMissCount;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.lang.reflect.Method;
import java.util.Map;

import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestResult;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Cache;

/**
 * A listener that validates the internal structure after a successful test execution.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class CacheValidationListener implements IInvokedMethodListener {

  @Override
  public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {}

  @Override
  public void afterInvocation(IInvokedMethod method, ITestResult testResult) {
    Method testMethod = testResult.getMethod().getConstructorOrMethod().getMethod();
    boolean checkNoStats = testMethod.isAnnotationPresent(CheckNoStats.class);
    try {
      if (testResult.isSuccess()) {
        for (Object param : testResult.getParameters()) {
          if (param instanceof Cache<?, ?>) {
            assertThat((Cache<?, ?>) param, is(validCache()));
          } else if (param instanceof AsyncLoadingCache<?, ?>) {
            assertThat((AsyncLoadingCache<?, ?>) param, is(validAsyncCache()));
          } else if (param instanceof Map<?, ?>) {
            assertThat((Map<?, ?>) param, is(validAsMap()));
          } else if (checkNoStats && (param instanceof CacheContext)) {
            checkNoStats = false;
            CacheContext context = (CacheContext) param;
            assertThat(context, hasHitCount(0));
            assertThat(context, hasMissCount(0));
            assertThat(context, hasLoadSuccessCount(0));
            assertThat(context, hasLoadFailureCount(0));
          }
        }
        if (checkNoStats) {
          throw new AssertionError("Test requires CacheContext param for validation");
        }
      }
    } catch (AssertionError caught) {
      testResult.setStatus(ITestResult.FAILURE);
      testResult.setThrowable(caught);
    } finally {
      cleanUp(testResult);
    }
  }

  /** Free memory by clearing unused resources after test execution. */
  private void cleanUp(ITestResult testResult) {
    for (int i = 0; i < testResult.getParameters().length; i++) {
      testResult.getParameters()[i] = testResult.getParameters()[i].toString();
    }
    CacheSpec.interner.remove();
  }
}
