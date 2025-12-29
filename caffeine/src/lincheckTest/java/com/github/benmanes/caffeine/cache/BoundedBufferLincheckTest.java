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

import static com.github.benmanes.caffeine.cache.Buffer.FAILED;
import static com.github.benmanes.caffeine.cache.Buffer.FULL;
import static com.github.benmanes.caffeine.cache.Buffer.SUCCESS;
import static com.github.benmanes.caffeine.cache.LincheckOptions.stress;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntBiFunction;

import org.jetbrains.lincheck.Lincheck;
import org.junit.jupiter.api.Test;

/**
 * The tests cases for the {@link BoundedBuffer}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class BoundedBufferLincheckTest {

  @Test
  void concurrent_offer() {
    Lincheck.runConcurrentTest(stress().invocationsPerIteration, () -> {
      try {
        int reads = 32;
        var buffer = new BoundedBuffer<Integer>();
        Runnable task = () -> {
          for (int i = 0; i < reads; i++) {
            int result = buffer.offer(i);
            assertTrue((result == SUCCESS) || (result == FAILED) || (result == FULL));
          }
        };
        var offer1 = new Thread(task);
        var offer2 = new Thread(task);
        offer1.start();
        offer2.start();
        offer1.join();
        offer2.join();
        assertTrue(buffer.size() >= BoundedBuffer.BUFFER_SIZE, () -> "Observed: " + buffer.size());
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  void concurrent_drain_offer() {
    checkConcurrent(BoundedBuffer::offer);
  }

  @Test
  void concurrent_drain_expandOrRetry() {
    checkConcurrent((buffer, e) -> buffer.expandOrRetry(e, e, e, /* wasUncontended= */ false));
  }

  private static void checkConcurrent(ToIntBiFunction<BoundedBuffer<Integer>, Integer> writer) {
    Lincheck.runConcurrentTest(stress().invocationsPerIteration, () -> {
      try {
        int reads = 32;
        var drained = new AtomicInteger();
        var buffer = new BoundedBuffer<Integer>();
        var offer = new Thread(() -> {
          for (int i = 0; i < reads; i++) {
            int result = writer.applyAsInt(buffer, i);
            assertTrue((result == SUCCESS) || (result == FAILED) || (result == FULL));
          }
        });
        var drain = new Thread(() -> buffer.drainTo(e -> drained.incrementAndGet()));
        offer.start();
        drain.start();
        offer.join();
        drain.join();
        int result = drained.get();
        assertTrue(result <= reads, () -> "Observed: " + result);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    });
  }
}
