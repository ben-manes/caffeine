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
 * This is the TinyCache model that takes advantage of random eviction policy with
 * a ghost cache as admission policy. It offers a very dense memory layout combined with
 * (relative) speed at the expense of limited associativity. s
 *
 * @author gilga1983@gmail.com (Gil Einziger)
 */
@SuppressWarnings("PMD.AvoidDollarSigns")
public final class TinyCache {
  private final HashFunctionParser hashFunc;
  public final long[] chainIndex;
  public final long[] lastIndex;
  private final int itemsPerSet;
  private final long[] cache;
  private final Random rnd;

  public TinyCache(int nrSets, int itemsPerSet, int randomSeed) {
    lastIndex = new long[nrSets];
    chainIndex = new long[nrSets];
    this.itemsPerSet = itemsPerSet;
    hashFunc = new HashFunctionParser(nrSets);
    cache = new long[nrSets * itemsPerSet];
    rnd = new Random(randomSeed);
  }

  public boolean contains(long item) {
    hashFunc.createHash(item);
    if (!TinySetIndexing.chainExist(chainIndex[hashFunc.fpaux.set], hashFunc.fpaux.chainId)) {
      return false;
    }
    TinySetIndexing.getChain(hashFunc.fpaux, chainIndex, lastIndex);
    int offset = this.itemsPerSet * hashFunc.fpaux.set;
    TinySetIndexing.chainStart += offset;
    TinySetIndexing.chainEnd += offset;

    // Gil : I think some of these tests are, I till carefully examine this function when I have
    // time. As far as I understand it is working right now.
    while (TinySetIndexing.chainStart <= TinySetIndexing.chainEnd) {
      try {
        if (cache[TinySetIndexing.chainStart % cache.length] == hashFunc.fpaux.value) {
          return true;
        }
        TinySetIndexing.chainStart++;
      } catch (Exception e) {
        System.out.println(" length: " + cache.length + " Access: " + TinySetIndexing.chainStart);
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
    this.cache[bucketStart + removedOffset] = 0;
    TinySetIndexing.removeItem(fpaux, chainIndex, lastIndex);
    fpaux.chainId = chainId;
    int idxToAdd = TinySetIndexing.addItem(fpaux, chainIndex, lastIndex);
    int delta = (removedOffset < idxToAdd) ? -1 : 1;
    replaceItems(idxToAdd, fpaux.value, bucketStart, delta);
    return removedOffset;
  }

  public boolean addItem(long item) {
    hashFunc.createHash(item);
    int bucketStart = this.itemsPerSet * hashFunc.fpaux.set;
    if (cache[bucketStart + this.itemsPerSet - 1] != 0) {
      return selectVictim(bucketStart);

    }
    int idxToAdd = TinySetIndexing.addItem(hashFunc.fpaux, chainIndex, lastIndex);
    this.replaceItems(idxToAdd, hashFunc.fpaux.value, bucketStart, 1);
    return false;
  }

  private boolean selectVictim(int bucketStart) {
    byte victimOffset = (byte) rnd.nextInt(this.itemsPerSet);
    int victimChain =
        TinySetIndexing.getChainAtOffset(hashFunc.fpaux, chainIndex, lastIndex, victimOffset);
    // this if is still for debugging and common sense. Should be eliminated for performance once
    // I am sure of the correctness.
    if (TinySetIndexing.chainExist(chainIndex[hashFunc.fpaux.set], victimChain)) {
      replace(hashFunc.fpaux, (byte) victimChain, bucketStart, victimOffset);
      return true;
    } else {
      throw new RuntimeException("Failed to replace");
    }
  }

  @SuppressWarnings("PMD.LocalVariableNamingConventions")
  private void replaceItems(final int idx, long value, int start, final int delta) {
    start += idx;
    long $;
    do {
      $ = this.cache[start];
      this.cache[start] = value;
      value = $;
      start += delta;
    } while (value != 0);
  }
}
