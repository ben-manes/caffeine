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

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A universally unique identifier represented as a 64-bit numeric value.
 * <p>
 * This implementation is based on Twitter's Snowflake id generation scheme. It uses a millisecond
 * timestamp with Twitter's custom epoch for 41-bits, the machine's id for 10-bits (up to 1024
 * machines), and a sequence number for 12-bits (rolls over at 4096).
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TracerIdGenerator {
  private static final long EPOCH = 1288834974657L;
  private static final int TIMESTAMP_SHIFT = 13;

  private static final int MAX_MACHINE_ID = 1024;
  private static final int MACHINE_ID_SHIFT = 54;
  private static final long MACHINE_ID = getMachineId();

  private static final int MAX_SEQUENCE_MASK = 4095;

  private long lastTimestamp;
  private int sequence;

  /** @return a unique identifier */
  public synchronized long nextId() {
    initializeSeeds();

    return ((lastTimestamp - EPOCH) << TIMESTAMP_SHIFT)
        | (MACHINE_ID << MACHINE_ID_SHIFT)
        | sequence;
  }

  /** Initializes the seed variables for generating the unique identifier. */
  private void initializeSeeds() {
    long timestamp = Math.max(lastTimestamp, System.currentTimeMillis());
    if (timestamp == lastTimestamp) {
      sequence = (sequence + 1) & MAX_SEQUENCE_MASK;
      if (sequence == 0) {
        timestamp += 1;
      }
    } else {
      sequence = 0;
    }
    lastTimestamp = timestamp;
  }

  /** @return the machine's id based on its hardware address */
  private static long getMachineId() {
    try {
      InetAddress ip = InetAddress.getLocalHost();
      byte[] mac = NetworkInterface.getByInetAddress(ip).getHardwareAddress();
      return ((0x000000FF & (long) mac[mac.length - 1])
          | (0x0000FF00 & (((long) mac[mac.length - 2]) << 8))) >> 6;
    } catch (SocketException | UnknownHostException e) {
      return ThreadLocalRandom.current().nextLong(MAX_MACHINE_ID);
    }
  }
}
