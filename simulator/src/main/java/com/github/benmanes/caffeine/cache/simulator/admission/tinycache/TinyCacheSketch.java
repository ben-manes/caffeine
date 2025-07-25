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

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Var;

/**
 * This is the TinyCache sketch that is based on TinySet and TinyTable. It is adopted for fast
 * operations and bounded memory footprint. When a set is full, a victim is selected at random from
 * the full set. (basically some sort of random removal cache).
 *
 * @author gilga1983@gmail.com (Gil Einziger)
 */
public final class TinyCacheSketch {
  private final HashFunctionParser hashFunc;
  private final TinySetIndexing indexing;
  private final long[] chainIndex;
  private final long[] lastIndex;
  private final int itemsPerSet;
  private final byte[] cache;
  private final Random rnd;

  public TinyCacheSketch(int nrSets, int itemsPerSet, int randomSeed) {
    this.hashFunc = new HashFunctionParser(nrSets);
    this.cache = new byte[nrSets * itemsPerSet];
    this.indexing = new TinySetIndexing();
    this.chainIndex = new long[nrSets];
    this.lastIndex = new long[nrSets];
    this.rnd = new Random(randomSeed);
    this.itemsPerSet = itemsPerSet;
  }

  public int countItem(long item) {
    hashFunc.createHash(item);
    if (!indexing.chainExist(chainIndex[hashFunc.fpaux.set], hashFunc.fpaux.chainId)) {
      return 0;
    }
    indexing.getChain(hashFunc.fpaux, chainIndex, lastIndex);
    int offset = itemsPerSet * hashFunc.fpaux.set;
    indexing.setChainStart(indexing.getChainStart() + offset);
    indexing.setChainEnd(indexing.getChainEnd() + offset);

    // Gil : I think some of these tests are, I will carefully examine this function when I have
    // time. As far as I understand it is working right now.
    @Var int count = 0;
    while (indexing.getChainStart() <= indexing.getChainEnd()) {
      try {
        if (cache[indexing.getChainStart() % cache.length] == hashFunc.fpaux.fingerprint) {
          count++;
        }
        indexing.setChainStart(indexing.getChainStart() + 1);

      } catch (RuntimeException _) {
        System.out.println("length: " + cache.length + " Access: " + indexing.getChainStart());
      }
    }
    return count;
  }

  /**
   * Implementing add and remove together in one function means that fewer items are shifted
   * (reduction of 3 times from the trivial implementation).
   */
  @CanIgnoreReturnValue
  private int replace(HashedItem fpaux, byte victim, int bucketStart, int removedOffset) {
    byte chainId = fpaux.chainId;
    fpaux.chainId = victim;

    cache[bucketStart + removedOffset] = 0;

    indexing.removeItem(fpaux, chainIndex, lastIndex);
    fpaux.chainId = chainId;
    int idxToAdd = indexing.addItem(fpaux, chainIndex, lastIndex);
    int delta = (removedOffset < idxToAdd) ? -1 : 1;

    replaceItems(idxToAdd, fpaux.fingerprint, bucketStart, delta);

    return removedOffset;
  }

  public void addItem(long item) {
    hashFunc.createHash(item);
    int bucketStart = itemsPerSet * hashFunc.fpaux.set;
    if (cache[bucketStart + itemsPerSet - 1] != 0) {
      selectVictim(bucketStart);
      return;
    }

    int idxToAdd = indexing.addItem(hashFunc.fpaux, chainIndex, lastIndex);
    replaceItems(idxToAdd, hashFunc.fpaux.fingerprint, bucketStart, 1);
  }

  @SuppressWarnings("Varifier")
  private void selectVictim(int bucketStart) {
    byte victimOffset = (byte) rnd.nextInt(itemsPerSet);
    int victimChain = indexing.getChainAtOffset(
        hashFunc.fpaux, chainIndex, lastIndex, victimOffset);
    if (indexing.chainExist(chainIndex[hashFunc.fpaux.set], victimChain)) {
      replace(hashFunc.fpaux, (byte) victimChain, bucketStart, victimOffset);
    } else {
      throw new IllegalStateException("Failed to replace");
    }
  }

  private void replaceItems(int idx, @Var byte value, @Var int start, int delta) {
    @Var byte entry;
    start += idx;
    do {
      entry = cache[start];
      cache[start] = value;
      value = entry;
      start += delta;
    } while (value != 0);
  }
}
