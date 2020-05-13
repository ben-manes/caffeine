/*
 * Copyright 2019 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.rewrite;

import java.io.BufferedWriter;
import java.io.IOException;

import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;

/**
 * The trace output format.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public enum OutputFormat {
  CLIMB {
    @Override public void write(BufferedWriter writer, AccessEvent event, long time) throws IOException {
      writer.write(Long.toString(event.key()));
      writer.newLine();
    }
  },
  ADAPT_SIZE {
    @Override public void write(BufferedWriter writer, AccessEvent event, long time) throws IOException {
      writer.write(Long.toString(time) + " ");
      writer.write(Long.toString(event.key()) + " ");
      writer.write(Integer.toString(event.weight()));
      writer.newLine();
    }
  };

  public abstract void write(BufferedWriter writer, AccessEvent event, long time) throws IOException;
}
