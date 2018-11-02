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
 * This is the TinyCache sketch that is based on TinySet and TinyTable. It is adopted for fast
 * operation and bounded memory footprint. When a set is full, a victim is selected at random from
 * the full set. (basically some sort of random removal cache).
 *
 * @author gilga1983@gmail.com (Gil Einziger)
 */
@SuppressWarnings({"PMD.AvoidDollarSigns", "PMD.LocalVariableNamingConventions"})
public final class TinyCacheSketch {
  public final long[] chainIndex;
  public final long[] lastIndex;

  private final HashFunctionParser hashFunc;
  private final int itemsPerSet;
  private final byte[] cache;
  private final Random rnd;

  public TinyCacheSketch(int nrSets, int itemsPerSet, int randomSeed) {
    chainIndex = new long[nrSets];
    lastIndex = new long[nrSets];
    this.itemsPerSet = itemsPerSet;
    hashFunc = new HashFunctionParser(nrSets);
    cache = new byte[nrSets * itemsPerSet];
    rnd = new Random(randomSeed);
  }

  public int countItem(long item) {
    hashFunc.createHash(item);
    int $ = 0;
    if (!TinySetIndexing.chainExist(chainIndex[hashFunc.fpaux.set], hashFunc.fpaux.chainId)) {
      return 0;
    }
    TinySetIndexing.getChain(hashFunc.fpaux, chainIndex, lastIndex);
    int offset = this.itemsPerSet * hashFunc.fpaux.set;
    TinySetIndexing.chainStart += offset;
    TinySetIndexing.chainEnd += offset;

    // Gil : I think some of these tests are, I till carefully examine this function when I have
    // time. As far as I understand it is working right now.
    while (TinySetIndexing.chainStart <= TinySetIndexing.chainEnd) {
      try {
        $ += (cache[TinySetIndexing.chainStart % cache.length] == hashFunc.fpaux.fingerprint)
            ? 1
            : 0;
        TinySetIndexing.chainStart++;

      } catch (Exception e) {
        System.out.println(" length: " + cache.length + " Access: " + TinySetIndexing.chainStart);
        // e.printStackTrace();
      }
    }
    return $;
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

    replaceItems(idxToAdd, fpaux.fingerprint, bucketStart, delta);

    return removedOffset;
  }

  public void addItem(long item) {
    hashFunc.createHash(item);
    int bucketStart = this.itemsPerSet * hashFunc.fpaux.set;
    if (cache[bucketStart + this.itemsPerSet - 1] != 0) {
      selectVictim(bucketStart);
      return;
    }

    int idxToAdd = TinySetIndexing.addItem(hashFunc.fpaux, chainIndex, lastIndex);
    this.replaceItems(idxToAdd, hashFunc.fpaux.fingerprint, bucketStart, 1);
  }

  private void selectVictim(int bucketStart) {
    byte victimOffset = (byte) rnd.nextInt(this.itemsPerSet);
    int victimChain =
        TinySetIndexing.getChainAtOffset(hashFunc.fpaux, chainIndex, lastIndex, victimOffset);
    if (TinySetIndexing.chainExist(chainIndex[hashFunc.fpaux.set], victimChain)) {
      replace(hashFunc.fpaux, (byte) victimChain, bucketStart, victimOffset);
    } else {
      throw new RuntimeException("Failed to replace");
    }
  }

  private void replaceItems(final int idx, byte value, int start, final int delta) {
    start += idx;
    byte $;
    do {
      $ = this.cache[start];
      this.cache[start] = value;
      value = $;
      start += delta;
    } while (value != 0);
  }
}
