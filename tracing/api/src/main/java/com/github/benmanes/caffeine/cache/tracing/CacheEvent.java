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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * An event created as a side-effect of an operation on a cache.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheEvent {
  public enum Action { CREATE, READ, READ_OR_CREATE, UPDATE, DELETE, EVICT }

  private Action action;
  private long timestamp;
  private int cacheId;
  private int hash;

  public CacheEvent() {}

  public CacheEvent(int cacheId, Action action, int hash, long timestamp) {
    this.timestamp = timestamp;
    this.cacheId = cacheId;
    this.action = action;
    this.hash = hash;
  }

  public Action action() {
    return action;
  }

  public void setAction(Action action) {
    this.action = action;
  }

  public long timestamp() {
    return timestamp;
  }

  public void setTimestamp(long timestamp) {
    this.timestamp = timestamp;
  }

  public int cacheId() {
    return cacheId;
  }

  public void setCacheId(int cacheId) {
    this.cacheId = cacheId;
  }

  public int hash() {
    return hash;
  }

  public void setHash(int hash) {
    this.hash = hash;
  }

  public static CacheEvent fromTextRecord(String record) {
    return fromTextRecord(record.split(" "));
  }

  public static CacheEvent fromTextRecord(String[] record) {
    CacheEvent event = new CacheEvent();
    event.action = Action.valueOf(record[0]);
    event.cacheId = Integer.parseInt(record[1]);
    event.hash = Integer.parseInt(record[2]);
    event.timestamp = Long.parseLong(record[3]);
    return event;
  }

  public void appendTextRecord(Appendable output) throws IOException {
    output.append(action.name());
    output.append(' ');
    output.append(Integer.toString(cacheId));
    output.append(' ');
    output.append(Integer.toString(hash));
    output.append(' ');
    output.append(Long.toString(timestamp));
    output.append(System.lineSeparator());
  }

  public static CacheEvent fromBinaryRecord(byte[] record) {
    throw new UnsupportedOperationException();
  }

  public byte[] toBinaryRecord() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    } else if (!(o instanceof CacheEvent)) {
      return false;
    }
    CacheEvent event = (CacheEvent) o;
    return Objects.equals(action, event.action)
        && Objects.equals(cacheId, event.cacheId)
        && Objects.equals(timestamp, event.timestamp)
        && Objects.equals(hash, event.hash);
  }

  @Override
  public int hashCode() {
    return Objects.hash(action, cacheId, timestamp, hash);
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
