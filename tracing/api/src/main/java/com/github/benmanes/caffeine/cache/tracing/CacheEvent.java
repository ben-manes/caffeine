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

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheEvent {
  public enum Action { CREATE, READ, UPDATE, DELETE, EVICT }

  Action action;

  long timestamp;
  int cacheId;
  int hash;

  public Action getAction() {
    return action;
  }

  public void setAction(Action action) {
    this.action = action;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(long timestamp) {
    this.timestamp = timestamp;
  }

  public int getCacheId() {
    return cacheId;
  }

  public void setCacheId(int cacheId) {
    this.cacheId = cacheId;
  }

  public int getHash() {
    return hash;
  }

  public void setHash(int hash) {
    this.hash = hash;
  }

  public static CacheEvent fromTextRecord(String record) {
    CacheEvent event = new CacheEvent();
    String[] column = record.split(" ");
    event.action = Action.valueOf(column[0]);
    event.cacheId = Integer.parseInt(column[1]);
    event.hash = Integer.parseInt(column[2]);
    event.timestamp = Long.parseLong(column[3]);
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
  public String toString() {
    return String.format("action=%s, cacheId=%d, hash=%d, timestamp=%d",
        action, cacheId, hash, timestamp);
  }
}
