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
import static java.util.stream.Collectors.toList;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.parser.adapt_size.AdaptSizeTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.address.AddressTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.address.penalties.AddressPenaltiesTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.arc.ArcTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.cache2k.Cache2kTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.camelab.CamelabTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.climb.ClimbTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.corda.CordaTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.gradle.GradleTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.kaggle.OutbrainTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.lirs.LirsTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.scarab.ScarabTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.snia.cambridge.CambridgeTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.snia.parallel.K5cloudTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.snia.parallel.TencentBlockTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.snia.parallel.TencentPhotoTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.twitter.TwitterTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.umass.network.YoutubeTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.umass.storage.StorageTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.wikipedia.WikipediaTraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

/**
 * The trace file formats.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("ImmutableEnumChecker")
public enum TraceFormat {
  ADDRESS(AddressTraceReader::new),
  ADDRESS_PENALTIES(AddressPenaltiesTraceReader::new),
  ADAPT_SIZE(AdaptSizeTraceReader::new),
  ARC(ArcTraceReader::new),
  CACHE2K(Cache2kTraceReader::new),
  CAMELAB(CamelabTraceReader::new),
  CLIMB(ClimbTraceReader::new),
  CORDA(CordaTraceReader::new),
  GRADLE(GradleTraceReader::new),
  LIRS(LirsTraceReader::new),
  OUTBRAIN(OutbrainTraceReader::new),
  SCARAB(ScarabTraceReader::new),
  SNIA_CAMBRIDGE(CambridgeTraceReader::new),
  SNIA_K5CLOUD(K5cloudTraceReader::new),
  SNIA_TENCENT_BLOCK(TencentBlockTraceReader::new),
  SNIA_TENCENT_PHOTO(TencentPhotoTraceReader::new),
  TWITTER(TwitterTraceReader::new),
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
    return new TraceReader() {

      @Override public Set<Characteristic> characteristics() {
        return readers().stream()
            .flatMap(reader -> reader.characteristics().stream())
            .collect(Sets.toImmutableEnumSet());
      }

      @Override public Stream<AccessEvent> events() {
        return readers().stream().flatMap(TraceReader::events);
      }

      private List<TraceReader> readers() {
        return filePaths.stream().map(path -> {
          List<String> parts = Splitter.on(':').limit(2).splitToList(path);
          TraceFormat format = (parts.size() == 1) ? TraceFormat.this : named(parts.get(0));
          return format.factory.apply(Iterables.getLast(parts));
        }).collect(toList());
      }
    };
  }

  /** Returns the format based on its configuration name. */
  public static TraceFormat named(String name) {
    return TraceFormat.valueOf(name.replace('-', '_').toUpperCase(US));
  }
}
