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

import java.util.Objects;
import java.util.stream.LongStream;

import org.apache.commons.lang3.StringUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.KeyOnlyTraceReader;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;

/**
 * A reader for the trace files provided by the <a href="http://www.wikibench.eu">wikibench</a>
 * project. The requests are sanitized and filtered using the <tt>TraceBench</tt> optimizations.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class WikipediaTraceReader extends TextTraceReader implements KeyOnlyTraceReader {
  private static final ImmutableList<String> CONTAINS_FILTER = ImmutableList.of("?search=",
      "&search=", "User+talk", "User_talk", "User:", "Talk:", "&diff=", "&action=rollback",
      "Special:Watchlist");
  private static final ImmutableList<String> STARTS_WITH_FILTER = ImmutableList.of(
      "wiki/Special:Search", "w/query.php", "wiki/Talk:", "wiki/Special:AutoLogin",
      "Special:UserLogin", "w/api.php", "error:");
  private static final ImmutableList<Replacement> REPLACEMENTS = ImmutableList.of(
      Replacement.of("%2F", "/"), Replacement.of("%20", " "),
      Replacement.of("&amp;", "&"), Replacement.of("%3A", ":"));

  public WikipediaTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public LongStream keys() {
    return lines()
        .map(this::parseRequest)
        .filter(Objects::nonNull)
        .mapToLong(path -> Hashing.murmur3_128().hashUnencodedChars(path).asLong());
  }

  /**
   * Returns the request's path or {@code null} if this request should be ignored. The input is
   * space deliminated with the following format,
   * <ul>
   *  <li>A monotonically increasing counter (useful for sorting the trace in chronological order)
   *  <li>The timestamp of the request in Unix notation with millisecond precision
   *  <li>The requested URL
   *  <li>A flag to indicate if the request resulted in a database update or not ('-' or 'save')
   * </ul>
   */
  private @Nullable String parseRequest(String line) {
    if (!isRead(line)) {
      return null;
    }
    String url = getRequestUrl(line);
    if (url.length() > 12) {
      String path = getPath(url);
      if (isAllowed(path)) {
        return path;
      }
    }
    return null;
  }

  /** Returns whether the request resulted in a write to the database. */
  private boolean isRead(String line) {
    return line.charAt(line.length() - 1) == '-';
  }

  /** Returns the request URL. */
  private String getRequestUrl(String line) {
    int end = line.length() - 2;
    while (line.charAt(end) != ' ') {
      end--;
    }

    int start = end - 1;
    while (line.charAt(start) != ' ') {
      start--;
    }
    return line.substring(start + 1, end);
  }

  /** Returns the path segment of the URL. */
  private String getPath(String url) {
    int index = url.indexOf('/', 7);
    if (index == -1) {
      return url;
    }

    // Replace the html entities that we want to search for inside paths
    String cleansed = url.substring(index + 1);
    for (var replacement : REPLACEMENTS) {
      cleansed = StringUtils.replace(cleansed, replacement.search(), replacement.replace());
    }
    return cleansed;
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

  @AutoValue
  static abstract class Replacement {
    abstract String search();
    abstract String replace();

    static Replacement of(String search, String replace) {
      return new AutoValue_WikipediaTraceReader_Replacement(search, replace);
    }
  }
}
