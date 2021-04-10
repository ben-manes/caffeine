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

import static com.github.benmanes.caffeine.cache.Async.ASYNC_EXPIRY;
import static com.github.benmanes.caffeine.cache.BoundedLocalCache.MAXIMUM_EXPIRY;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.mockito.Mockito;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Async.AsyncExpiry;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AsyncTest {
  private static final long ONE_MINUTE = TimeUnit.MINUTES.toNanos(1);

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
    ConcurrentTestHarness.execute(() -> {
      result.set(1);
      result.set(Async.getWhenSuccessful(future));
    });
    await().untilAtomic(result, is(1));
    future.obtrudeValue(2);
    await().untilAtomic(result, is(2));
  }

  @Test(dataProvider = "unsuccessful")
  public void getWhenSuccessful_fails(CompletableFuture<?> future) {
    if ((future != null) && !future.isDone()) {
      AtomicInteger result = new AtomicInteger();
      ConcurrentTestHarness.execute(() -> {
        result.set(1);
        Object value = Async.getWhenSuccessful(future);
        result.set((value == null) ? 2 : 3);
      });
      await().untilAtomic(result, is(1));
      future.obtrudeException(new IllegalStateException());
      await().untilAtomic(result, is(not(1)));
      assertThat(result.get(), is(2));
    }
    assertThat(Async.getWhenSuccessful(future), is(nullValue()));
  }

  @Test
  public void asyncExpiry_pending() {
    AsyncExpiry<Integer, Integer> expiry = makeAsyncExpiry(ONE_MINUTE, ONE_MINUTE, ONE_MINUTE);
    CompletableFuture<Integer> future = new CompletableFuture<Integer>();

    assertThat(expiry.expireAfterCreate(0, future, 1L), is(ASYNC_EXPIRY));
    verify(expiry.delegate, never()).expireAfterCreate(any(), any(), anyLong());

    assertThat(expiry.expireAfterUpdate(0, future, 1L, 2L), is(ASYNC_EXPIRY));
    verify(expiry.delegate, never()).expireAfterUpdate(any(), any(), anyLong(), anyLong());

    assertThat(expiry.expireAfterRead(0, future, 1L, 2L), is(ASYNC_EXPIRY));
    verify(expiry.delegate, never()).expireAfterRead(any(), any(), anyLong(), anyLong());
  }

  @Test
  public void asyncExpiry_completed() {
    AsyncExpiry<Integer, Integer> expiry = makeAsyncExpiry(
        ONE_MINUTE, 2 * ONE_MINUTE, 3 * ONE_MINUTE);
    CompletableFuture<Integer> future = CompletableFuture.completedFuture(100);

    assertThat(expiry.expireAfterCreate(0, future, 1L), is(ONE_MINUTE));
    verify(expiry.delegate).expireAfterCreate(0, 100, 1L);

    assertThat(expiry.expireAfterUpdate(0, future, 1L, 2L), is(2 * ONE_MINUTE));
    verify(expiry.delegate).expireAfterUpdate(0, 100, 1L, 2L);

    assertThat(expiry.expireAfterRead(0, future, 1L, 2L), is(3 * ONE_MINUTE));
    verify(expiry.delegate).expireAfterRead(0, 100, 1L, 2L);
  }

  @Test
  public void asyncExpiry_bounded() {
    AsyncExpiry<Integer, Integer> expiry = makeAsyncExpiry(
        Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    CompletableFuture<Integer> future = CompletableFuture.completedFuture(100);

    assertThat(expiry.expireAfterCreate(0, future, 1L), is(MAXIMUM_EXPIRY));
    assertThat(expiry.expireAfterUpdate(0, future, 1L, 2L), is(MAXIMUM_EXPIRY));
    assertThat(expiry.expireAfterRead(0, future, 1L, 2L), is(MAXIMUM_EXPIRY));
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
        { CompletableFuture.completedFuture(null) },
        { newFailedFuture(new InterruptedException()) },
        { newFailedFuture(new IllegalStateException()) },
    };
  }

  private static <K, V> AsyncExpiry<K, V> makeAsyncExpiry(long create, long update, long read) {
    @SuppressWarnings("unchecked")
    Expiry<K, V> mock = Mockito.mock(Expiry.class);
    when(mock.expireAfterCreate(any(), any(), anyLong())).thenReturn(create);
    when(mock.expireAfterUpdate(any(), any(), anyLong(), anyLong())).thenReturn(update);
    when(mock.expireAfterRead(any(), any(), anyLong(), anyLong())).thenReturn(read);
    return new AsyncExpiry<>(mock);
  }

  private static CompletableFuture<Integer> newFailedFuture(Exception e) {
    CompletableFuture<Integer> future = new CompletableFuture<Integer>();
    future.completeExceptionally(e);
    return future;
  }
}
