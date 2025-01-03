/*
 * Copyright (c) 2004, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.github.benmanes.caffeine.openjdk.concurrent.concurrenthashmap;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/*
 * @test
 * @bug 4486658 8010293
 * @summary thread safety of toArray methods of collection views
 * @author Martin Buchholz
 */
@SuppressWarnings({"AlmostJavadoc", "CollectorMutability",
  "IdentifierName", "ShortCircuitBoolean", "UnnecessaryFinal"})
public class ToArray {

    @Test
    public void bounded() {
      test(() -> Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofNanos(Long.MAX_VALUE))
          .maximumSize(Long.MAX_VALUE)
          .build());
    }

    @Test
    public void unbounded() {
      test(() -> Caffeine.newBuilder().build());
    }

    public static void test(Supplier<Cache<Integer, Integer>> supplier) {
        final int runsPerTest = Integer.getInteger("jsr166.runsPerTest", 1);
        final int reps = 10 * runsPerTest;
        for (int i = reps; i--> 0; ) {
            executeTest(supplier.get().asMap());
        }
    }

    static void executeTest(ConcurrentMap<Integer, Integer> m) {
      var executor = Executors.newCachedThreadPool();
      try {
            final ThreadLocalRandom rnd = ThreadLocalRandom.current();
            final int nCPU = Runtime.getRuntime().availableProcessors();
            final int minWorkers = 2;
            final int maxWorkers = Math.max(minWorkers, Math.min(32, nCPU));
            final int nWorkers = rnd.nextInt(minWorkers, maxWorkers + 1);
            final int sizePerWorker = 1024;
            final int maxSize = nWorkers * sizePerWorker;

            // The foreman busy-checks that the size of the arrays obtained
            // from the keys and values views grows monotonically until it
            // reaches the maximum size.

            // NOTE: these size constraints are not specific to toArray and are
            // applicable to any form of traversal of the collection views
            CompletableFuture<?> foreman = CompletableFuture.runAsync(new Runnable() {
                private int prevSize = 0;

                private boolean checkProgress(Object[] a) {
                    int size = a.length;
                    if (size < prevSize || size > maxSize) {
                      throw new AssertionError(
                              String.format(Locale.US, "prevSize=%d size=%d maxSize=%d",
                                      prevSize, size, maxSize));
                    }
                    prevSize = size;
                    return size == maxSize;
                }

                @Override
                public void run() {
                    Integer[] empty = new Integer[0];
                    for (; ; ) {
                      if (checkProgress(m.values().toArray())
                              & checkProgress(m.keySet().toArray())
                              & checkProgress(m.values().toArray(empty))
                              & checkProgress(m.keySet().toArray(empty))) {
                        return;
                      }
                    }
                }
            }, executor);

            // Each worker puts globally unique keys into the map
            List<CompletableFuture<?>> workers =
                    IntStream.range(0, nWorkers)
                            .mapToObj(w -> (Runnable) () -> {
                                for (int i = 0, o = w * sizePerWorker; i < sizePerWorker; i++) {
                                  m.put(o + i, i);
                                }
                            })
                            .map(r -> CompletableFuture.runAsync(r, executor))
                            .collect(Collectors.toList());

            // Wait for workers and foreman to complete
            workers.forEach(CompletableFuture<?>::join);
            foreman.join();
        } finally {
            executor.shutdown();
        }
    }
}
