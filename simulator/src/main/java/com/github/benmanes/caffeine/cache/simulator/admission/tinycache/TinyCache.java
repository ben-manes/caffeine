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

import java.util.Random;

/**
 * This is the TinyCache model that takes advantage of random eviction policy with a ghost cache as
 * admission policy. It offers a very dense memory layout combined with (relative) speed at the
 * expense of limited associativity.
 *
 * @author gilga1983@gmail.com (Gil Einziger)
 */
public final class TinyCache {
  private final HashFunctionParser hashFunc;
  private final TinySetIndexing indexing;
  private final long[] chainIndex;
  private final long[] lastIndex;
  private final int itemsPerSet;
  private final long[] cache;
  private final Random rnd;

  public TinyCache(int nrSets, int itemsPerSet, int randomSeed) {
    this.hashFunc = new HashFunctionParser(nrSets);
    this.cache = new long[nrSets * itemsPerSet];
    this.indexing = new TinySetIndexing();
    this.chainIndex = new long[nrSets];
    this.lastIndex = new long[nrSets];
    this.rnd = new Random(randomSeed);
    this.itemsPerSet = itemsPerSet;
  }

  public boolean contains(long item) {
    hashFunc.createHash(item);
    if (!indexing.chainExist(chainIndex[hashFunc.fpaux.set], hashFunc.fpaux.chainId)) {
      return false;
    }
    indexing.getChain(hashFunc.fpaux, chainIndex, lastIndex);
    int offset = itemsPerSet * hashFunc.fpaux.set;
    indexing.chainStart += offset;
    indexing.chainEnd += offset;

    // Gil: I will carefully examine this function when I have time.
    // As far as I understand it is working right now.
    while (indexing.chainStart <= indexing.chainEnd) {
      try {
        if (cache[indexing.chainStart % cache.length] == hashFunc.fpaux.value) {
          return true;
        }
        indexing.chainStart++;
      } catch (Exception e) {
        System.out.println("length: " + cache.length + " Access: " + indexing.chainStart);
      }
    }
    return false;
  }

  /**
   * Implementing add and remove together in one function, means that less items are shifted.
   * (reduction of 3 times from trivial implementation).
   */
  private int replace(HashedItem fpaux, byte victim, int bucketStart, int removedOffset) {
    byte chainId = fpaux.chainId;
    fpaux.chainId = victim;
    cache[bucketStart + removedOffset] = 0;
    indexing.removeItem(fpaux, chainIndex, lastIndex);
    fpaux.chainId = chainId;
    int idxToAdd = indexing.addItem(fpaux, chainIndex, lastIndex);
    int delta = (removedOffset < idxToAdd) ? -1 : 1;
    replaceItems(idxToAdd, fpaux.value, bucketStart, delta);
    return removedOffset;
  }

  public boolean addItem(long item) {
    hashFunc.createHash(item);
    int bucketStart = itemsPerSet * hashFunc.fpaux.set;
    if (cache[bucketStart + itemsPerSet - 1] != 0) {
      return selectVictim(bucketStart);

    }
    int idxToAdd = indexing.addItem(hashFunc.fpaux, chainIndex, lastIndex);
    replaceItems(idxToAdd, hashFunc.fpaux.value, bucketStart, 1);
    return false;
  }

  private boolean selectVictim(int bucketStart) {
    byte victimOffset = (byte) rnd.nextInt(itemsPerSet);
    int victimChain = indexing.getChainAtOffset(
        hashFunc.fpaux, chainIndex, lastIndex, victimOffset);
    // this is still for debugging and common sense. Should be eliminated for performance once
    // I am sure of the correctness.
    if (indexing.chainExist(chainIndex[hashFunc.fpaux.set], victimChain)) {
      replace(hashFunc.fpaux, (byte) victimChain, bucketStart, victimOffset);
      return true;
    } else {
      throw new RuntimeException("Failed to replace");
    }
  }

  private void replaceItems(int idx, long value, int start, int delta) {
    start += idx;
    long entry;
    do {
      entry = cache[start];
      cache[start] = value;
      value = entry;
      start += delta;
    } while (value != 0);
  }
}
