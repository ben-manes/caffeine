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
 * This is a wrapper class that represents a parsed hashed item. It contains set - a subtable for
 * the item. chain - a logical index within the set. fingerprint - a value to be stored in the set
 * and chain. This implementation assumes fingerprints of 1 byte. .
 *
 * @author gilga1983@gmail.com (Gil Einziger)
 */
final class HashedItem {
  int set;
  byte chainId;
  byte fingerprint;
  long value;

  public HashedItem(int set, byte chainId, byte fingerprint, long value) {
    this.set = set;
    this.value = value;
    this.chainId = chainId;
    this.fingerprint = fingerprint;
  }

  @Override
  public String toString() {
    return ("BucketID: " + set + " chainID:" + chainId + " fingerprint: " + fingerprint);
  }
}
