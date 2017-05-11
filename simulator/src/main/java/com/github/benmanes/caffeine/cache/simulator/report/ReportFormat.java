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
package com.github.benmanes.caffeine.cache.simulator.report;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.typesafe.config.Config;

/**
 * The report data formats.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("ImmutableEnumChecker")
public enum ReportFormat {
  TABLE(TableReporter::new),
  CSV(CsvReporter::new);

  private final Function<Config, Reporter> factory;

  ReportFormat(Function<Config, Reporter> factory) {
    this.factory = requireNonNull(factory);
  }

  public Reporter create(Config config) {
    return factory.apply(config);
  }
}
