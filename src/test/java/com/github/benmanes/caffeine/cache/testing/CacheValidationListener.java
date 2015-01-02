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

import static com.github.benmanes.caffeine.cache.IsValidCache.validate;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestResult;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.TestGarbageCollector;
import com.github.benmanes.caffeine.cache.testing.RemovalListeners.ConsumingRemovalListener;
import com.github.benmanes.caffeine.cache.testing.RemovalListeners.RejectingRemovalListener;

/**
 * A listener that validates the internal structure after a successful test execution.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheValidationListener implements IInvokedMethodListener {

  @Override
  public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {}

  @Override
  public void afterInvocation(IInvokedMethod method, ITestResult testResult) {
    try {
      if (testResult.isSuccess()) {
        for (Object param : testResult.getParameters()) {
          if (param instanceof Cache<?, ?>) {
            assertThat((Cache<?, ?>) param, is(validate()));
          }
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
    for (Object param : testResult.getParameters()) {
      if (param instanceof CacheContext) {
        CacheContext context = (CacheContext) param;
        if (context.removalListener instanceof RejectingRemovalListener<?, ?>) {
          ((RejectingRemovalListener<?, ?>) context.removalListener).reject = false;
        }
        context.cache.invalidateAll();
        context.original().clear();
        if (context.removalListener instanceof ConsumingRemovalListener<?, ?>) {
          ((ConsumingRemovalListener<?, ?>) context.removalListener).evicted().clear();
        }
        TestGarbageCollector.discard(context.cache);
      }
    }
  }
}
