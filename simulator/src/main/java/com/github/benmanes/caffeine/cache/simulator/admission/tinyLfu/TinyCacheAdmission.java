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

import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;

/**
 * @author gilga1983@gmail.com (Gilga Einziger)
 */
public final class TinyCacheAdmission implements Admittor {
  private final HashFunction hashFunc;
  private final int itemsPerSet;
  private final byte[] cache;
  private final long[] index;
  private int nrItems;

  public TinyCacheAdmission(int nrSets, int itemsPerSet) {
    index = new long[nrSets * 2];
    hashFunc = new HashFunction(nrSets, 64);
    this.itemsPerSet = itemsPerSet;
    cache = new byte[nrSets * (itemsPerSet)];
  }

  @Override
  public void record(Object key) {
    this.addItem(key.hashCode());
  }

  @Override
  public boolean admit(Object candidateKey, Object victimKey) {
    int candidateScore = this.countItem(candidateKey.hashCode());
    int victimScore = this.countItem(victimKey.hashCode());
    return (victimScore <= candidateScore);
  }

  public int countItem(long item) {
    hashFunc.createHash(item);
    int $ = 0;
    boolean doesChainExist = TinySetIndexingTechnique.chainExist(
        index[hashFunc.fpaux.bucketId << 1], hashFunc.fpaux.chainId);
    if (!doesChainExist) {
      return 0;
    }
    TinySetIndexingTechnique.getChain(hashFunc.fpaux, index);
    int offset = this.itemsPerSet * hashFunc.fpaux.bucketId;
    TinySetIndexingTechnique.ChainStart += offset;
    TinySetIndexingTechnique.ChainEnd += offset;

    while (TinySetIndexingTechnique.ChainStart < TinySetIndexingTechnique.ChainEnd) {
      $ += (cache[TinySetIndexingTechnique.ChainStart++] == hashFunc.fpaux.fingerprint) ? 1L : 0L;
    }
    return $;
  }

  public void printBucket(int bucketId) {
    for (int i = 0; i < 63; i++) {
      if ((index[bucketId << 1] & (1L << i)) != 0) {
        System.out.print("Chain: " + i + " ");

        int initial = TinySetIndexingTechnique
            .getChainStart(new FingerPrintAuxByteFP(bucketId, (byte) i, (byte) 333), index);
        int end = TinySetIndexingTechnique
            .getChainEnd(new FingerPrintAuxByteFP(bucketId, (byte) i, (byte) 8383), index);
        for (int j = initial; j <= end; j++) {
          System.out.print(this.cache[bucketId * this.itemsPerSet + j] + " ");
        }

        System.out.println("--- start: " + initial + " End " + end);
      }
    }
  }

  public void addItem(String item) {
    this.nrItems++;
    hashFunc.createHash(item);
    addItemOnceHashReady();
  }

  public void addItem(long item) {
    this.nrItems++;
    hashFunc.createHash(item);
    addItemOnceHashReady();
  }

  private void addItemOnceHashReady() {
    int bucketStart = this.itemsPerSet * hashFunc.fpaux.bucketId;

    if (this.cache[bucketStart + this.itemsPerSet - 1] != 0L) {
      byte victim = (byte) (this.nrItems & 63);
      int I0Offset = hashFunc.fpaux.bucketId << 1;
      while ((index[I0Offset] & (1L << victim)) == 0L) {
        victim++;
        victim &= 63;
      }
      replace(hashFunc.fpaux, victim, bucketStart);
      return;
    }
    int idxToAdd = TinySetIndexingTechnique.addItem(hashFunc.fpaux, index);
    putAndPush(idxToAdd, hashFunc.fpaux.fingerprint, bucketStart);
  }

  private int replace(FingerPrintAuxByteFP fpaux, byte victim, int bucketStart) {
    byte chainId = fpaux.chainId;
    fpaux.chainId = victim;

    int removedOffset = TinySetIndexingTechnique.getChainEnd(fpaux, index);
    this.cache[bucketStart + removedOffset] = 0;

    TinySetIndexingTechnique.RemoveItem(fpaux, index);
    fpaux.chainId = chainId;
    int idxToAdd = TinySetIndexingTechnique.addItem(fpaux, index);
    if (removedOffset < idxToAdd) {
      replaceBackwards(bucketStart, idxToAdd, fpaux.fingerprint);
    } else {
      putAndPush(idxToAdd, fpaux.fingerprint, bucketStart);
    }
    return removedOffset;
  }

  protected void replaceBackwards(int start, final int maxToShift, byte value) {
    start += maxToShift;
    byte $;
    do {
      $ = this.cache[start];
      this.cache[start] = value;
      value = $;
      start--;
    } while (value != 0L);
  }

  protected void putAndPush(final int idx, byte value, int start) {
    start += idx;
    byte $;
    do {
      $ = this.cache[start];
      this.cache[start] = value;
      value = $;
      start++;
    } while (value != 0L);
  }
}
