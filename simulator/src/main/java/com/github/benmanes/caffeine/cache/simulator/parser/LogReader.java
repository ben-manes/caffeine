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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.github.benmanes.caffeine.cache.tracing.CacheEvent;
import com.univocity.parsers.csv.CsvFormat;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;

/**
 * A pull-based reader of a cache tracing log in either text or binary format.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LogReader {

  private LogReader() {}

  /**
   * Creates a {@link Stream} that lazily reads the log file in the binary format.
   *
   * @param filePath the path to the log file
   * @return a lazy stream of cache events
   */
  public static Stream<CacheEvent> binaryLogStream(Path filePath) throws IOException {
    DataInputStream input = new DataInputStream(
        new BufferedInputStream(Files.newInputStream(filePath)));
    Spliterator<CacheEvent> spliterator = Spliterators.spliteratorUnknownSize(
        new BinaryLogIterator(input), Spliterator.NONNULL);
    return StreamSupport.stream(spliterator, false);
  }

  /**
   * Creates a {@link Stream} that lazily reads the log file in the text format.
   *
   * @param filePath the path to the log file
   * @return a lazy stream of cache events
   */
  public static Stream<CacheEvent> textLogStream(Path filePath) throws IOException {
    BufferedReader reader = Files.newBufferedReader(filePath);
    Spliterator<CacheEvent> spliterator = Spliterators.spliteratorUnknownSize(
        new TextLogIterator(reader), Spliterator.NONNULL);
    return StreamSupport.stream(spliterator, false);
  }

  private static final class BinaryLogIterator implements Iterator<CacheEvent> {
    final DataInputStream input;

    BinaryLogIterator(DataInputStream input) {
      this.input = input;
    }

    @Override
    public boolean hasNext() {
      try {
        input.mark(1);
        if (input.readByte() < 0) {
          input.close();
          return false;
        }
        input.reset();
        return true;
      } catch (EOFException e) {
        return false;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public CacheEvent next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      try {
        return CacheEvent.fromBinaryRecord(input);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private static final class TextLogIterator implements Iterator<CacheEvent> {
    final CsvParser parser;
    CacheEvent next;

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
      next = CacheEvent.fromTextRecord(record);
      return true;
    }

    @Override
    public CacheEvent next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      CacheEvent current = next;
      next = null;
      return current;
    }
  }
}
