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

import static java.util.Locale.US;

import java.util.List;
import java.util.function.Function;
import java.util.stream.LongStream;

import com.github.benmanes.caffeine.cache.simulator.parser.address.AddressTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.arc.ArcTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.cache2k.Cache2kTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.climb.ClimbTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.corda.CordaTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.gradle.GradleTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.lirs.LirsTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.scarab.ScarabTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.snia.cambridge.CambridgeTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.umass.network.YoutubeTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.umass.storage.StorageTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.wikipedia.WikipediaTraceReader;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;

/**
 * The trace file formats.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("ImmutableEnumChecker")
public enum TraceFormat {
  ADDRESS(AddressTraceReader::new),
  ARC(ArcTraceReader::new),
  CACHE2K(Cache2kTraceReader::new),
  CLIMB(ClimbTraceReader::new),
  CORDA(CordaTraceReader::new),
  GRADLE(GradleTraceReader::new),
  LIRS(LirsTraceReader::new),
  SCARAB(ScarabTraceReader::new),
  SNIA_CAMBRIDGE(CambridgeTraceReader::new),
  UMASS_STORAGE(StorageTraceReader::new),
  UMASS_YOUTUBE(YoutubeTraceReader::new),
  WIKIPEDIA(WikipediaTraceReader::new);

  private final Function<String, TraceReader> factory;

  TraceFormat(Function<String, TraceReader> factory) {
    this.factory = factory;
  }

  /**
   * Returns a new reader for streaming the events from the trace file.
   *
   * @param filePaths the path to the files in the trace's format
   * @return a reader for streaming the events from the file
   */
  public TraceReader readFiles(List<String> filePaths) {
    return () -> {
      LongStream events = LongStream.empty();
      for (String path : filePaths) {
        List<String> parts = Splitter.on(':').limit(2).splitToList(path);
        TraceFormat format = (parts.size() == 1) ? this : named(parts.get(0));
        LongStream next = format.factory.apply(Iterables.getLast(parts)).events();
        events = LongStream.concat(events, next);
      }
      return events;
    };
  }

  /** Returns the format based on its configuration name. */
  public static TraceFormat named(String name) {
    return TraceFormat.valueOf(name.replace('-', '_').toUpperCase(US));
  }
}
