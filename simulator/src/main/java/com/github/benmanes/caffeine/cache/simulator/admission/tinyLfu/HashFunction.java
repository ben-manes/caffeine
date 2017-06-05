/*
 * Copyright 2015 Gilga Einziger. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.admission.tinylfu;

/**
 * @author gilga1983@gmail.com (Gilga Einziger)
 */
final class HashFunction {
  private final static long M = 0xc6a4a7935bd1e995L;
  private final static long SEED_64 = 0xe17a1465;
  private final static int R = 47;

  // currently chain is bounded to be 64
  private final static long CHAIN_MASK = 63L;

  private final int fpSize;
  private final byte fpMask;
  private final int bucketRange;

  // used as return value - to avoid dynamic memory allocation.
  public final FingerPrintAuxByteFP fpaux;

  public HashFunction(int bucketRange, int chainrange) {
    fpSize = 8; // For efficiency we use 8 bit finger prints
    fpMask = (byte) 255; // 8 bits set to 1.
    this.bucketRange = bucketRange;
    // allocate just once so that we do not generate garbage
    fpaux = new FingerPrintAuxByteFP(fpMask, fpMask, fpMask);
  }

  // can accept stings
  public FingerPrintAuxByteFP createHash(String item) {
    final byte[] data = item.getBytes();
    long hash = MinorImprovedMurmurHash.hash64(data, data.length);
    fpaux.fingerprint = (byte) (hash & fpMask);
    if (fpaux.fingerprint == 0L) {
      fpaux.fingerprint++;
    }

    hash >>>= fpSize;
    fpaux.chainId = (byte) (hash & CHAIN_MASK);
    hash >>>= 6;
    fpaux.bucketId = (int) ((hash & Long.MAX_VALUE) % bucketRange);

    return fpaux;
  }

  public FingerPrintAuxByteFP createHash(long item) {
    long h = (SEED_64) ^ (M);
    item *= M;
    item ^= item >>> R;
    item *= M;

    h ^= item;
    h *= M;

    fpaux.fingerprint = (byte) (h & fpMask);
    fpaux.fingerprint = (fpaux.fingerprint == 0L) ? 1 : fpaux.fingerprint;

    h >>>= fpSize;
    fpaux.chainId = (byte) (h & CHAIN_MASK);
    h >>>= 6;
    fpaux.bucketId = (int) ((h & Long.MAX_VALUE) % bucketRange);

    return fpaux;
  }
}
