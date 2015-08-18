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
final class TinySetIndexingTechnique {
  // for performance - for functions that need to know both the start and the end of the chain.
  public static int ChainStart;
  public static int ChainEnd;

  public static int getChainStart(FingerPrintAuxByteFP fpaux, long[] I0, long[] IStar) {
    int requiredChainNumber = rank(I0[fpaux.bucketId], fpaux.chainId);
    int currentChainNumber = rank(IStar[fpaux.bucketId], requiredChainNumber);
    int currentOffset = requiredChainNumber;
    long tempIStar = IStar[fpaux.bucketId] >>> requiredChainNumber;
    while (currentChainNumber < requiredChainNumber) {
      currentChainNumber += tempIStar & 1L;
      currentOffset++;
      tempIStar >>>= 1;
    }
    return currentOffset;
  }

  public static int rank(long Istar, int idx) {
    return Long.bitCount(Istar & ((1L << idx) - 1));
  }

  public static int getChainStart(FingerPrintAuxByteFP FingerPrintAuxByteFP, long[] index) {
    int I0Offset = FingerPrintAuxByteFP.bucketId << 1;
    int IStarOffset = I0Offset + 1;
    int requiredChainNumber = rank(index[I0Offset], FingerPrintAuxByteFP.chainId);
    int currentChainNumber = rank(index[IStarOffset], requiredChainNumber);
    int currentOffset = requiredChainNumber;
    long tempIStar = index[IStarOffset] >>> requiredChainNumber;
    while (currentChainNumber < requiredChainNumber) {
      currentChainNumber += tempIStar & 1L;
      currentOffset++;
      tempIStar >>>= 1;
    }
    return currentOffset;
  }

  public static int getChainEnd(FingerPrintAuxByteFP fpaux, long[] I0, long[] IStar) {
    int requiredChainNumber = rank(I0[fpaux.bucketId], fpaux.chainId);
    int currentChainNumber = rank(IStar[fpaux.bucketId], requiredChainNumber);
    int currentOffset = requiredChainNumber;

    long tempIStar = IStar[fpaux.bucketId] >>> requiredChainNumber;
    while (currentChainNumber < requiredChainNumber) {
      currentChainNumber += tempIStar & 1L;
      currentOffset++;
      tempIStar >>>= 1;
    }
    while ((tempIStar & 1L) == 0) {
      currentOffset++;
      tempIStar >>>= 1;

    }
    return currentOffset;
  }

  public static int getChainEnd(FingerPrintAuxByteFP FingerPrintAuxByteFP, long[] index) {
    int I0Offset = FingerPrintAuxByteFP.bucketId << 1;
    int IStarOffset = I0Offset + 1;

    int requiredChainNumber = rank(index[I0Offset], FingerPrintAuxByteFP.chainId);
    int currentChainNumber = rank(index[IStarOffset], requiredChainNumber);
    int currentOffset = requiredChainNumber;

    long tempIStar = index[IStarOffset] >>> requiredChainNumber;
    while (currentChainNumber < requiredChainNumber) {
      currentChainNumber += tempIStar & 1L;
      currentOffset++;
      tempIStar >>>= 1;
    }
    while ((tempIStar & 1L) == 0) {
      currentOffset++;
      tempIStar >>>= 1;
    }
    return currentOffset;
  }

  public static int getChain(FingerPrintAuxByteFP fpaux, long[] index) {
    int BaseOffset = fpaux.bucketId << 1;
    int ISarrOffset = BaseOffset + 1;
    int requiredChainNumber = rank(index[BaseOffset], fpaux.chainId);
    int currentChainNumber = rank(index[ISarrOffset], requiredChainNumber);
    int currentOffset = requiredChainNumber;

    long tempIStar = index[ISarrOffset] >>> requiredChainNumber;
    while (currentChainNumber < requiredChainNumber) {
      currentChainNumber += tempIStar & 1L;
      currentOffset++;
      tempIStar >>>= 1;
    }
    TinySetIndexingTechnique.ChainStart = currentOffset;
    while ((tempIStar & 1L) == 0) {
      currentOffset++;
      tempIStar >>>= 1;
    }
    TinySetIndexingTechnique.ChainEnd = currentOffset;
    return currentOffset;
  }

  public static int getChain(FingerPrintAuxByteFP fpaux, long[] I0, long[] IStar) {
    int requiredChainNumber = rank(I0[fpaux.bucketId], fpaux.chainId);
    int currentChainNumber = rank(IStar[fpaux.bucketId], requiredChainNumber);
    int currentOffset = requiredChainNumber;

    long tempIStar = IStar[fpaux.bucketId] >>> requiredChainNumber;
    while (currentChainNumber < requiredChainNumber) {
      currentChainNumber += tempIStar & 1L;
      currentOffset++;
      tempIStar >>>= 1;
    }
    TinySetIndexingTechnique.ChainStart = currentOffset;
    while ((tempIStar & 1L) == 0) {
      currentOffset++;
      tempIStar >>>= 1;
    }
    TinySetIndexingTechnique.ChainEnd = currentOffset;
    return currentOffset;
  }

  public static boolean chainExist(long I0, int chainId) {
    return (I0 | (1L << chainId)) == I0;
  }

  public static int addItem(FingerPrintAuxByteFP fpaux, long[] I0, long[] IStar) {
    int offset = getChainStart(fpaux, I0, IStar);
    long mask = 1L << fpaux.chainId;
    IStar[fpaux.bucketId] = extendZero(IStar[fpaux.bucketId], offset);

    // if the item is new...
    if ((mask | I0[fpaux.bucketId]) != I0[fpaux.bucketId]) {
      // add new chain to IO.
      I0[fpaux.bucketId] |= mask;
      // mark item as last in IStar.
      IStar[fpaux.bucketId] |= (1L << offset);
    }

    return offset;
  }

  public static int addItem(FingerPrintAuxByteFP fpaux, long[] index) {
    int BaseOffset = fpaux.bucketId << 1;
    int ISarrOffset = BaseOffset + 1;
    int offset = getChainStart(fpaux, index);
    long mask = 1L << fpaux.chainId;
    index[ISarrOffset] = extendZero(index[ISarrOffset], offset);

    // if the item is new...
    if ((mask | index[BaseOffset]) != index[BaseOffset]) {
      // add new chain to IO.
      index[BaseOffset] |= mask;
      // mark item as last in IStar.
      index[ISarrOffset] |= (1L << offset);
    }
    return offset;
  }

  private static long extendZero(final long IStar, final int offset) {
    long constantPartMask = (1L << offset) - 1;
    return (IStar & constantPartMask) | ((IStar << 1L) & (~(constantPartMask)) & (~(1L << offset)));
  }

  private static long shrinkOffset(long IStar, int offset) {
    long conMask = ((1L << offset) - 1);
    return (IStar & conMask) | (((~conMask) & IStar) >>> 1);
  }

  public static void RemoveItem(FingerPrintAuxByteFP fpaux, long[] I0, long[] IStar) {
    int chainStart = getChainStart(fpaux, I0, IStar);

    // avoid an if command: either update I0 to the new state or keep it the way it is.
    I0[fpaux.bucketId] = (IStar[fpaux.bucketId] & (1L << chainStart)) != 0L
        ? I0[fpaux.bucketId] & ~(1L << fpaux.chainId)
        : I0[fpaux.bucketId];

    // update IStar.
    IStar[fpaux.bucketId] = shrinkOffset(IStar[fpaux.bucketId], chainStart);
  }

  public static void RemoveItem(FingerPrintAuxByteFP fpaux, long[] index) {
    int BaseOffset = fpaux.bucketId << 1;
    int ISarrOffset = BaseOffset + 1;
    int chainStart = getChainStart(fpaux, index);

    // avoid an if command: either update I0 to the new state or keep it the way it is.
    index[BaseOffset] = (index[ISarrOffset] & (1L << chainStart)) != 0L
        ? index[BaseOffset] & ~(1L << fpaux.chainId)
        : index[BaseOffset];

    // update IStar.
    index[ISarrOffset] = shrinkOffset(index[ISarrOffset], chainStart);
  }
}
