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
package com.github.benmanes.caffeine.cache.simulator.admission.tinycache;

/**
 * This is a hash function and parser tp simplify parsing the hash value, it split it to . This
 * class provide hash utilities, and parse the items.
 *
 * @author gilga1983@gmail.com (Gil Einziger)
 */
public final class HashFunctionParser {
  // currently chain is bounded to be 64.
  private static final int fpSize = 8; // this implementation assumes byte.
  private static final byte fpMask = (byte) 255; // (all bits in byte are 1, (logical value of -1));
  private static final long chainMask = 63L; // (6 first bit are set to 1).
  private final int nrSets;
  public final HashedItem fpaux; // used just to avoid allocating new memory as a return value.
  private final static long Seed64 = 0xe17a1465;
  private final static long m = 0xc6a4a7935bd1e995L;
  private final static int r = 47;

  public HashFunctionParser(int nrSets) {
    this.nrSets = nrSets;
    fpaux = new HashedItem(fpMask, fpMask, fpMask, 0L);
  }

  public HashedItem createHash(long item) {
    long h = (Seed64 ^ m);
    item *= m;
    item ^= item >>> r;
    item *= m;

    h ^= item;
    h *= m;

    fpaux.fingerprint = (byte) h;
    // the next line is a dirty fix as I do not want the value of 0 as a fingerprint.
    // It can be eliminated if we want very short fingerprints.
    fpaux.fingerprint = (fpaux.fingerprint == 0L) ? 1 : fpaux.fingerprint;
    h >>>= fpSize;
    fpaux.chainId = (byte) (h & chainMask);
    h >>>= 6;
    fpaux.set = (int) ((h & Long.MAX_VALUE) % nrSets);

    fpaux.value = (item << 1) | 1;
    if (item == 0) {
      fpaux.value = 1;
    }
    return fpaux;
  }
}
