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

import java.io.Reader;
import java.util.function.Consumer;

import com.github.benmanes.caffeine.cache.tracing.CacheEvent;
import com.univocity.parsers.common.ParsingContext;
import com.univocity.parsers.common.processor.AbstractRowProcessor;
import com.univocity.parsers.csv.CsvFormat;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;

/**
 * A parser that reads the log and publishes a stream of records to the consumer.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TextLogReader {

  private TextLogReader() {}

  public void process(Reader reader, Consumer<CacheEvent> consumer) {
    CsvFormat format = new CsvFormat();
    format.setDelimiter(' ');

    CsvParserSettings settings = new CsvParserSettings();
    settings.setFormat(format);
    settings.setRowProcessor(new AbstractRowProcessor() {
      @Override public void rowProcessed(String[] row, ParsingContext context) {
        consumer.accept(CacheEvent.fromTextRecord(context.currentParsedContent()));
      }
    });

    CsvParser parser = new CsvParser(settings);
    parser.parse(reader);
  }
}
