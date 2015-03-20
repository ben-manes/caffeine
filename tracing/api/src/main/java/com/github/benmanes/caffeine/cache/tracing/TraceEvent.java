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
import java.io.UncheckedIOException;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * An event created as a side-effect of an operation on a cache.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TraceEvent {
  public enum Action { REGISTER, READ, WRITE, DELETE }

  private long timestamp;
  private Action action;
  private String name;
  private int keyHash;
  private int weight;
  private long id;

  /** Creates an unconfigured cache event. */
  public TraceEvent() {}

  /**
   * Creates a configured trace event.
   *
   * @param name the name of the cache instance
   * @param id the unique id of the cache instance
   * @param action the action performed
   * @param keyHash the key's hash code
   * @param weight the entry's weight
   * @param timestamp the time
   */
  public TraceEvent(String name, long id, Action action, int keyHash, int weight, long timestamp) {
    this.timestamp = timestamp;
    this.keyHash = keyHash;
    this.action = action;
    this.name = name;
    this.id = id;
  }

  /** @return the name of the cache. */
  public @Nullable String name() {
    return name;
  }

  /**
   * Specifies the name of the cache instance.
   *
   * @param name the name of the cache
   */
  public void setName(@Nullable String name) {
    this.name = name;
  }

  /** @return the unqiue id of the cache */
  public long id() {
    return id;
  }

  /**
   * Specifies the unique id of the cache.
   *
   * @param id the cache's unique id
   */
  public void setId(long id) {
    this.id = id;
  }

  /** @return the action recorded */
  public @Nullable Action action() {
    return action;
  }

  /**
   * Specifies the action recorded by the tracer.
   *
   * @param action the traced cache operation
   */
  public void setAction(@Nullable Action action) {
    this.action = action;
  }

  /** @return the key's hash code */
  public int keyHash() {
    return keyHash;
  }

  /**
   * Specifies the hash code of the key.
   *
   * @param keyHash the key's hash code
   */
  public void setKeyHash(int keyHash) {
    this.keyHash = keyHash;
  }

  /** @return the weight change of an entry */
  public int weight() {
    return weight;
  }

  /**
   * Specifies the weight (or changed weight) of an entry.
   *
   * @param weight the entry's weight change
   */
  public void setWeight(int weight) {
    this.weight = weight;
  }

  /** @return the time when this event occurred */
  public long timestamp() {
    return timestamp;
  }

  /**
   * Specifies the time when the event occurred.
   *
   * @param timestamp the time when the event happened
   */
  public void setTimestamp(long timestamp) {
    this.timestamp = timestamp;
  }

  /**
   * Copies the event to the internal fields.
   *
   * @param event the prototype to copy from
   */
  public void copyFrom(TraceEvent event) {
    this.timestamp = event.timestamp;
    this.keyHash = event.keyHash;
    this.action = event.action;
    this.weight = event.weight;
    this.name = event.name;
    this.id = event.id;
  }

  /**
   * Returns an event read from the text record format (space separated).
   *
   * @param record the columns in the text record
   * @return the event that the record represents
   */
  public static TraceEvent fromTextRecord(@Nonnull String[] record) {
    TraceEvent event = new TraceEvent();
    int index = 0;
    event.action = Action.valueOf(record[index++]);
    if (event.action == Action.REGISTER) {
      event.name = record[index++];
    }
    event.id = Long.parseLong(record[index++]);
    if (event.action != Action.REGISTER) {
      event.keyHash = Integer.parseInt(record[index++]);
      event.weight = Integer.parseInt(record[index++]);
    }
    event.timestamp = Long.parseLong(record[index++]);
    return event;
  }

  /**
   * Writes the event as a textual record.
   *
   * @param output the text sink
   * @throws IOException if the output cannot be written
   */
  public void appendTextRecord(@Nonnull Appendable output) throws IOException {
    output.append(action.name());
    output.append(' ');
    if (action == Action.REGISTER) {
      output.append(name);
      output.append(' ');
    }
    output.append(Long.toString(id));
    output.append(' ');
    if (action != Action.REGISTER) {
      output.append(Integer.toString(keyHash));
      output.append(' ');
      output.append(Integer.toString(weight));
      output.append(' ');
    }
    output.append(Long.toString(timestamp));
  }

  /**
   * Returns the next event read from the binary record format.
   *
   * @param input the binary data stream
   * @return the event that the next record represents
   * @throws IOException if the input cannot be read
   */
  public static TraceEvent fromBinaryRecord(@Nonnull DataInputStream input) throws IOException {
    TraceEvent event = new TraceEvent();
    event.action = Action.values()[input.readShort()];
    if (event.action == Action.REGISTER) {
      int length = input.readInt();
      char[] chars = new char[length];
      for (int i = 0; i < length; i++) {
        chars[i] = input.readChar();
      }
      event.name = String.valueOf(chars);
    }
    event.id = input.readLong();
    if (event.action != Action.REGISTER) {
      event.keyHash = input.readInt();
      event.weight = input.readInt();
    }
    event.timestamp = input.readLong();
    return event;
  }

  /**
   * Writes the event as a binary record.
   *
   * @param output the binary sink
   * @throws IOException if the output cannot be written
   */
  public void appendBinaryRecord(@Nonnull DataOutputStream output) throws IOException {
    output.writeShort(action.ordinal());
    if (action == Action.REGISTER) {
      output.writeInt(name.length());
      output.writeChars(name);
    }
    output.writeLong(id);
    if (action != Action.REGISTER) {
      output.writeInt(keyHash);
      output.writeInt(weight);
    }
    output.writeLong(timestamp);
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    } else if (!(o instanceof TraceEvent)) {
      return false;
    }
    TraceEvent event = (TraceEvent) o;
    return Objects.equals(timestamp, event.timestamp)
        && Objects.equals(keyHash, event.keyHash)
        && Objects.equals(action, event.action)
        && Objects.equals(weight, event.weight)
        && Objects.equals(name, event.name)
        && Objects.equals(id, event.id);
  }

  @Override
  public int hashCode() {
    return (int) (timestamp ^ id);
  }

  @Override
  public String toString() {
    try {
      StringBuilder output = new StringBuilder();
      appendTextRecord(output);
      return output.toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
