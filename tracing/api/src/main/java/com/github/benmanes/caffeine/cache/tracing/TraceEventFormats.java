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
package com.github.benmanes.caffeine.cache.tracing;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

import com.github.benmanes.caffeine.cache.tracing.TraceEvent.Action;

/**
 * Static utility methods for reading and writing {@link TraceEvent} records in the text or binary
 * file formats.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@ThreadSafe
public final class TraceEventFormats {

  private TraceEventFormats() {}

  /* ---------------- Text -------------- */

  /**
   * Returns an event read from the text record format (space separated).
   *
   * @param record the columns in the text record
   * @return the event that the record represents
   */
  @Nonnull
  public static TraceEvent readTextRecord(@Nonnull String[] record) {
    int index = 0;
    TraceEvent event = new TraceEvent();
    event.setAction(Action.valueOf(record[index++]));
    if (event.action() == Action.REGISTER) {
      event.setName(record[index++]);
    }
    event.setId(Long.parseLong(record[index++]));
    if (event.action() != Action.REGISTER) {
      event.setKeyHash(Integer.parseInt(record[index++]));
      event.setWeight(Integer.parseInt(record[index++]));
    }
    event.setTimestamp(Long.parseLong(record[index++]));
    return event;
  }

  /**
   * Writes the event as a textual record.
   *
   * @param event the event to write
   * @param output the text sink
   * @throws IOException if the output cannot be written
   */
  public static void writeTextRecord(@Nonnull TraceEvent event,
      @Nonnull Appendable output) throws IOException {
    output.append(event.action().name());
    output.append(' ');
    if (event.action() == Action.REGISTER) {
      output.append(event.name().replace(' ', '_'));
      output.append(' ');
    }
    output.append(Long.toString(event.id()));
    output.append(' ');
    if (event.action() != Action.REGISTER) {
      output.append(Integer.toString(event.keyHash()));
      output.append(' ');
      output.append(Integer.toString(event.weight()));
      output.append(' ');
    }
    output.append(Long.toString(event.timestamp()));
  }

  /* ---------------- Binary -------------- */

  /**
   * Returns the next event read from the binary record format.
   *
   * @param input the binary data stream
   * @return the event that the next record represents
   * @throws IOException if the input cannot be read
   */
  @Nonnull
  public static TraceEvent readBinaryRecord(@Nonnull DataInputStream input) throws IOException {
    TraceEvent event = new TraceEvent();
    event.setAction(Action.values()[input.readShort()]);
    if (event.action() == Action.REGISTER) {
      int length = input.readInt();
      char[] chars = new char[length];
      for (int i = 0; i < length; i++) {
        chars[i] = input.readChar();
      }
      event.setName(String.valueOf(chars));
    }
    event.setId(input.readLong());
    if (event.action() != Action.REGISTER) {
      event.setKeyHash(input.readInt());
      event.setWeight(input.readInt());
    }
    event.setTimestamp(input.readLong());
    return event;
  }

  /**
   * Writes the event as a binary record.
   *
   * @param event the event to write
   * @param output the binary sink
   * @throws IOException if the output cannot be written
   */
  public static void writeBinaryRecord(@Nonnull TraceEvent event,
      @Nonnull DataOutputStream output) throws IOException {
    output.writeShort(event.action().ordinal());
    if (event.action() == Action.REGISTER) {
      output.writeInt(event.name().length());
      output.writeChars(event.name());
    }
    output.writeLong(event.id());
    if (event.action() != Action.REGISTER) {
      output.writeInt(event.keyHash());
      output.writeInt(event.weight());
    }
    output.writeLong(event.timestamp());
  }
}
