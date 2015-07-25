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
package com.github.benmanes.caffeine.cache.simulator.parser.caffeine;

import static java.util.Objects.requireNonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader;
import com.github.benmanes.caffeine.cache.tracing.TraceEvent;
import com.github.benmanes.caffeine.cache.tracing.TraceEventFormats;
import com.univocity.parsers.csv.CsvFormat;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;

/**
 * A reader for the Caffeine trace files in the text format.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeineTextTraceReader implements TraceReader<TraceEvent> {
  private final Path filePath;

  public CaffeineTextTraceReader(Path filePath) {
    this.filePath = requireNonNull(filePath);
  }

  @Override
  public Stream<TraceEvent> events() throws IOException {
    BufferedReader reader = Files.newBufferedReader(filePath);
    Spliterator<TraceEvent> spliterator = Spliterators.spliteratorUnknownSize(
        new TextLogIterator(reader), Spliterator.NONNULL);
    return StreamSupport.stream(spliterator, false);
  }

  private static final class TextLogIterator implements Iterator<TraceEvent> {
    final CsvParser parser;
    TraceEvent next;

    TextLogIterator(Reader reader) {
      parser = makeParser();
      parser.beginParsing(reader);
    }

    private static CsvParser makeParser() {
      CsvFormat format = new CsvFormat();
      format.setDelimiter(' ');
      CsvParserSettings settings = new CsvParserSettings();
      settings.setFormat(format);
      return new CsvParser(settings);
    }

    @Override
    public boolean hasNext() {
      if (next != null) {
        return true;
      }
      String[] record = parser.parseNext();
      if (record == null) {
        parser.stopParsing();
        return false;
      }
      next = TraceEventFormats.readTextRecord(record);
      return true;
    }

    @Override
    public TraceEvent next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      TraceEvent current = next;
      next = null;
      return current;
    }
  }
}
