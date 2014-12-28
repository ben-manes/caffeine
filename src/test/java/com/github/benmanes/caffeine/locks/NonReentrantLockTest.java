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
package com.github.benmanes.caffeine.locks;

import static com.jayway.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class NonReentrantLockTest {

  @DataProvider(name = "lock")
  public Object[][] providesLock() {
    return new Object[][] {{ new NonReentrantLock() }};
  }

  @Test(dataProvider = "lock")
  public void tryLock(NonReentrantLock lock) {
    assertThat(lock.tryLock(), is(true));
    lock.unlock();
  }

  @Test(dataProvider = "lock")
  public void lock(NonReentrantLock lock) {
    lock.lock();
    assertThat(lock.tryLock(), is(false));
    lock.unlock();
  }

  @Test(dataProvider = "lock")
  public void exclusive(NonReentrantLock lock) {
    new Thread(() -> {
      lock.lock();
      await().until(() -> lock.hasQueuedThreads());
      lock.unlock();
    }).start();
    await().until(() -> lock.isLocked());
    assertThat(lock.tryLock(), is(false));
    lock.lock();
    lock.unlock();
  }
}
