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
package com.github.benmanes.caffeine.cache.simulator.parser;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;
import picocli.CommandLine.ExitCode;

/**
 * The rewriter is driven from scripts and CI, so a rejected command must be distinguishable from a
 * completed one by its exit status, and a mistyped output path must not consume the trace it was
 * asked to read.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class RewriterTest {

  @Test
  void rewrite(@TempDir Path dir) throws IOException {
    Path input = Files.writeString(dir.resolve("in.trace"), "1\n2\n1\n");
    Path output = dir.resolve("out.trace");

    assertThat(execute(input, output).exitCode()).isEqualTo(ExitCode.OK);
    assertThat(Files.readAllLines(output)).containsExactly("1", "2", "1").inOrder();
  }

  @Test
  void outputIsAnInput(@TempDir Path dir) throws IOException {
    Path input = Files.writeString(dir.resolve("in.trace"), "1\n2\n1\n");

    var result = execute(input, input);

    assertThat(result.exitCode()).isEqualTo(ExitCode.SOFTWARE);
    assertThat(result.failure()).isInstanceOf(IllegalArgumentException.class);
    assertThat(result.failure()).hasMessageThat().contains("also an input file");
    assertThat(Files.readAllLines(input)).containsExactly("1", "2", "1").inOrder();
  }

  @Test
  void missingRequiredOption(@TempDir Path dir) throws IOException {
    Path input = Files.writeString(dir.resolve("in.trace"), "1\n");
    var command = new CommandLine(Rewriter.class).setCaseInsensitiveEnumValuesAllowed(true);
    command.setErr(command.getOut());

    int exitCode = command.execute(
        "--inputFiles", input.toString(), "--inputFormat", "lirs", "--outputFormat", "lirs");

    assertThat(exitCode).isEqualTo(ExitCode.USAGE);
  }

  private static Result execute(Path input, Path output) {
    var failure = new AtomicReference<Exception>();
    var command = new CommandLine(Rewriter.class).setCaseInsensitiveEnumValuesAllowed(true);
    command.setExecutionExceptionHandler((e, _, _) -> {
      failure.set(e);
      return ExitCode.SOFTWARE;
    });
    int exitCode = command.execute("--inputFiles", input.toString(), "--inputFormat", "lirs",
        "--outputFile", output.toString(), "--outputFormat", "lirs");
    return new Result(exitCode, failure.get());
  }

  private record Result(int exitCode, Exception failure) {}
}
