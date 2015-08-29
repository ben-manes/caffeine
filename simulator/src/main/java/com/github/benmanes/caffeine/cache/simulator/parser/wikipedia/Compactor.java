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
package com.github.benmanes.caffeine.cache.simulator.parser.wikipedia;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.common.base.Charsets;
import com.google.common.base.Stopwatch;
import com.univocity.parsers.common.ParsingContext;
import com.univocity.parsers.common.processor.AbstractRowProcessor;
import com.univocity.parsers.csv.CsvFormat;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import com.univocity.parsers.csv.CsvWriter;
import com.univocity.parsers.csv.CsvWriterSettings;

/**
 * Reads in the request data and compacts it into a trace that is efficient to process.
 * <p>
 * The data is taken from <a href="http://www.wikibench.eu">wikibench</a> and data optimizations
 * were based on <tt>TraceBench</tt>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Compactor implements Runnable {

  /*
   * The trace comprises one request per line. Each line contains:
   *  - A monotonically increasing counter (useful for sorting the trace in chronological order)
   *  - The timestamp of the request in Unix notation with millisecond precision
   *  - The requested URL
   *  - A flag to indicate if the request resulted in a database update or not
   */

  private final Path inPath;
  private final Path outPath;

  public Compactor(Path inPath, Path outPath) {
    this.inPath = requireNonNull(inPath);
    this.outPath = requireNonNull(outPath);
  }

  @Override
  public void run() {
    try (Reader reader = newReader()) {
      CsvParser parser = newParser();
      parser.parse(reader);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Reader newReader() throws IOException {
    GZIPInputStream stream = new GZIPInputStream(Files.newInputStream(inPath));
    Reader reader = new InputStreamReader(stream, Charsets.UTF_8);
    return new BufferedReader(reader);
  }

  private CsvParser newParser() throws IOException {
    CsvFormat format = new CsvFormat();
    format.setDelimiter(' ');
    CsvParserSettings settings = new CsvParserSettings();
    settings.setFormat(format);
    settings.selectIndexes(2, 3);
    settings.setRowProcessor(new WikipediaRowProcessor(outPath));
    settings.setInputBufferSize(10 * settings.getInputBufferSize());
    return new CsvParser(settings);
  }

  private static final class WikipediaRowProcessor extends AbstractRowProcessor {
    private static final String[] CONTAINS_FILTER = {
        "?search=", "&search=",
        "User+talk", "User_talk",
        "User:", "Talk:",
        "&diff=", "&action=rollback",
        "Special:Watchlist",
    };
    private static final String[] STARTS_WITH_FILTER = {
        "wiki/Special:Search",
        "w/query.php",
        "wiki/Talk:",
        "wiki/Special:AutoLogin", "Special:UserLogin",
        "w/api.php",
    };
    private static final long MASK = (1 << 20) - 1;

    private final Map<String, String> urlToPath;
    private final Map<String, String> pathToId;
    private final CsvWriter writer;
    private long lineNumber;
    private long nextId;

    public WikipediaRowProcessor(Path outPath) throws IOException {
      writer = new CsvWriter(newWriter(outPath), new CsvWriterSettings());
      urlToPath = new HashMap<>();
      pathToId = new HashMap<>();
    }

    private Writer newWriter(Path outPath) throws IOException {
      GZIPOutputStream stream = new GZIPOutputStream(Files.newOutputStream(outPath));
      return new BufferedWriter(new OutputStreamWriter(stream, Charsets.UTF_8));
    }

    @Override
    public void rowProcessed(String[] row, ParsingContext context) {
      lineNumber = context.currentLine();
      if ((lineNumber & MASK) == 0) {
        System.out.printf("Processed %,d lines%n", lineNumber);
      }
      if ((row[0].length() > 12) && !row[1].equals("save")) {
        String url = row[0].substring(7);
        String path = getPath(url);
        if (isAllowed(path)) {
          String id = pathToId.computeIfAbsent(path, key -> Long.toString(++nextId));
          writer.writeRow(id);
        }
      }
    }

    /** Returns the path segment of the url. */
    private String getPath(String url) {
      return urlToPath.computeIfAbsent(url, key -> {
        int index = key.indexOf("/");
        if (index == -1) {
          return "";
        }
        String path = key.substring(index + 1);

        // Replace the html entities that we want to search for inside paths
        path = path.replaceAll("%2F", "/");
        path = path.replaceAll("%20", " ");
        path = path.replaceAll("&amp;", "&");
        path = path.replaceAll("%3A", ":");

        return path;
      });
    }

    /**
     * Returns if the path should be included. The request is ignored if it is a search query, a
     * page revision, related to users or user management, or talk pages.
     */
    public boolean isAllowed(String path) {
      for (String filter : STARTS_WITH_FILTER) {
        if (path.startsWith(filter)) {
          return false;
        }
      }
      for (String filter : CONTAINS_FILTER) {
        if (path.contains(filter)) {
          return false;
        }
      }
      return true;
    }

    @Override
    public void processEnded(ParsingContext context) {
      writer.close();
    }
  }

  public static void main(String[] args) {
    checkArgument(args.length == 2, "Compactor [input file] [output file]");
    Compactor compactor = new Compactor(Paths.get(args[0]), Paths.get(args[1]));
    Stopwatch stopwatch = Stopwatch.createStarted();

    compactor.run();
    System.out.println("Executed in " + stopwatch);
  }
}
