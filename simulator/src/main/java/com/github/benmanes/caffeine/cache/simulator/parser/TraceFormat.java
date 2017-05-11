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
package com.github.benmanes.caffeine.cache.simulator.parser;

import java.util.List;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.simulator.parser.address.AddressTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.arc.ArcTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.cache2k.Cache2kTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.gradle.GradleTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.lirs.LirsTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.scarab.ScarabTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.umass.network.YoutubeTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.umass.storage.StorageTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.wikipedia.WikipediaTraceReader;

/**
 * The trace file formats.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("ImmutableEnumChecker")
public enum TraceFormat {
  ADDRESS(AddressTraceReader::new),
  ARC(ArcTraceReader::new),
  GRADLE(GradleTraceReader::new),
  LIRS(LirsTraceReader::new),
  UMASS_STORAGE(StorageTraceReader::new),
  UMASS_YOUTUBE(YoutubeTraceReader::new),
  WIKIPEDIA(WikipediaTraceReader::new),
  CACHE2K(Cache2kTraceReader::new),
  SCARAB(ScarabTraceReader::new);

  private final Function<List<String>, TraceReader> factory;

  TraceFormat(Function<List<String>, TraceReader> factory) {
    this.factory = factory;
  }

  /**
   * Returns a new reader for streaming the events from the trace file.
   *
   * @param filePaths the path to the files in the trace's format
   * @return a reader for streaming the events from the file
   */
  public TraceReader readFiles(List<String> filePaths) {
    return factory.apply(filePaths);
  }
}
