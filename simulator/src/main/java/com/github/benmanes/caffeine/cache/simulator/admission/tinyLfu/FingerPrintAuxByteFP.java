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
final class FingerPrintAuxByteFP {
  public int bucketId;
  public byte chainId;
  public byte fingerprint;

  public FingerPrintAuxByteFP(int bucketid, byte chainid, byte i) {
    this.bucketId = bucketid;
    this.chainId = chainid;
    this.fingerprint = i;
  }

  // Finger prints are only eaual if they differ at most at the first bit
  // the first bit is used as an index.
  public static boolean isEquals(long $1, long $2) {
    // $1 = $1^$2;
    return (($1 ^ $2) < 2L);
  }

  // the item is last in chain iff it is marked by 1 on the LSB.
  public static boolean isLast(long fingerPrint) {
    return ((fingerPrint & 1) == 1);
  }

  // marks an item as last.
  public static long setLast(long fingerPrint) {
    return fingerPrint | 1;
  }

  @Override
  public String toString() {
    return ("BucketID: " + bucketId + " chainID:" + chainId + " fingerprint: " + fingerprint);
  }
}
