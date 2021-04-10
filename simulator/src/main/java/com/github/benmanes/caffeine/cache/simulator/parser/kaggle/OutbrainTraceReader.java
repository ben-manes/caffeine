/*
 * Copyright 2020 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.kaggle;

import java.util.stream.LongStream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader.KeyOnlyTraceReader;

/**
 * A reader for the page views log provided by
 * <a href="https://www.kaggle.com/c/outbrain-click-prediction/data">Outbrain</a>.
 * Notice: you should reorder the file according to the timestamp, e.g. by
 * {@code sort -t, -k 3,3n -s page_views_sample.csv > page_views_sample_sorted.csv}
 *
 * @author ohadey@gmail.com (Ohad Eytan)
 */
public final class OutbrainTraceReader extends TextTraceReader implements KeyOnlyTraceReader {

  public OutbrainTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public LongStream keys() {
    return lines().skip(1)
        .map(line -> line.split(","))
        .mapToLong(array -> Long.parseLong(array[1]));
  }
}
