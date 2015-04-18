/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.lang.reflect.Constructor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.testing.Awaits;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AsyncTest {

  @Test
  public void reflectivelyConstruct() throws Exception {
    Constructor<?> constructor = Async.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    constructor.newInstance();
  }

  @Test(dataProvider = "successful")
  public void isReady_success(CompletableFuture<?> future) {
    assertThat(Async.isReady(future), is(true));
  }

  @Test(dataProvider = "unsuccessful")
  public void isReady_fails(CompletableFuture<?> future) {
    assertThat(Async.isReady(future), is(false));
  }

  @Test(dataProvider = "successful")
  public void getIfReady_success(CompletableFuture<?> future) {
    assertThat(Async.getIfReady(future), is(1));
  }

  @Test(dataProvider = "unsuccessful")
  public void getIfReady_fails(CompletableFuture<?> future) {
    assertThat(Async.getIfReady(future), is(nullValue()));
  }

  @Test(dataProvider = "successful")
  public void getWhenSuccessful_success(CompletableFuture<?> future) {
    assertThat(Async.getWhenSuccessful(future), is(1));
  }

  @Test
  public void getWhenSuccessful_success_async() {
    CompletableFuture<Integer> future = new CompletableFuture<Integer>();
    AtomicInteger result = new AtomicInteger();
    ForkJoinPool.commonPool().execute(() -> {
      result.set(1);
      result.set(Async.getWhenSuccessful(future));
    });
    Awaits.await().untilAtomic(result, is(1));
    future.obtrudeValue(2);
    Awaits.await().untilAtomic(result, is(2));
  }

  @Test(dataProvider = "unsuccessful")
  public void getWhenSuccessful_fails(CompletableFuture<?> future) {
    if ((future != null) && !future.isDone()) {
      AtomicInteger result = new AtomicInteger();
      ForkJoinPool.commonPool().execute(() -> {
        result.set(1);
        Object value = Async.getWhenSuccessful(future);
        result.set((value == null) ? 2 : 3);
      });
      Awaits.await().untilAtomic(result, is(1));
      future.obtrudeException(new IllegalStateException());
      Awaits.await().untilAtomic(result, is(not(1)));
      assertThat(result.get(), is(2));
    }
    assertThat(Async.getWhenSuccessful(future), is(nullValue()));
  }

  @DataProvider(name = "successful")
  public Object[][] providesSuccessful() {
    return new Object[][] {{ CompletableFuture.completedFuture(1) }};
  }

  @DataProvider(name = "unsuccessful")
  public Object[][] providesUnsuccessful() {
    return new Object[][] {
        { null },
        { new CompletableFuture<Integer>() },
        { newFailedFuture(new InterruptedException()) },
        { newFailedFuture(new IllegalStateException()) },
    };
  }

  private static CompletableFuture<Integer> newFailedFuture(Exception e) {
    CompletableFuture<Integer> future = new CompletableFuture<Integer>();
    future.completeExceptionally(e);
    return future;
  }
}
