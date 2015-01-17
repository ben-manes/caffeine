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
package com.github.benmanes.caffeine.cache.tracing.async;

import java.io.IOException;

/**
 *
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public enum LogFormat {
  TEXT {
    @Override public void appendTo(Appendable output, int id,
        String action, int hashCode, long timestamp) throws IOException {
      output.append(action);
      output.append(' ');
      output.append(Integer.toString(id));
      output.append(' ');
      output.append(Integer.toString(hashCode));
      output.append(' ');
      output.append(Long.toString(timestamp));
      output.append(System.lineSeparator());
    }
  },
  BINARY {
    @Override public void appendTo(Appendable output, int id,
        String action, int hashCode, long timestamp) throws IOException {
      throw new UnsupportedOperationException();
    }
  };

  public abstract void appendTo(Appendable output, int id,
      String action, int hashCode, long timestamp) throws IOException;
}
