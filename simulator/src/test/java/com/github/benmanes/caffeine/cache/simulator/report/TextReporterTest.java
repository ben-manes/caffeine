/*
 * Copyright 2026 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.report;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.report.table.TableReporter;
import com.typesafe.config.ConfigFactory;

/**
 * The console reporter writes through System.out, which the process owns. Printing a report must
 * not close it, or the caller's later output (e.g. the run's elapsed time) is silently dropped.
 *
 * @author sahvx655@gmail.com (Sahana Bogar)
 */
final class TextReporterTest {

  @Test
  @SuppressWarnings({"PMD.CloseResource", "SystemOut"})
  void print_console_keepsSystemOutOpen() {
    var original = System.out;
    var stats = new PolicyStats("test");
    stats.recordHit();
    stats.recordMiss();
    var config = ConfigFactory.load().getConfig("caffeine.simulator");
    try (var console = new CloseAwarePrintStream(OutputStream.nullOutputStream())) {
      System.setOut(console);
      new TableReporter(config, Set.of()).print(List.of(stats));
      assertThat(console.closed).isFalse();
    } finally {
      System.setOut(original);
    }
  }

  private static final class CloseAwarePrintStream extends PrintStream {
    boolean closed;

    CloseAwarePrintStream(OutputStream out) {
      super(out, /* autoFlush= */ true, UTF_8);
    }

    @Override
    public void close() {
      closed = true;
      super.close();
    }
  }
}
