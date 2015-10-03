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
package com.github.benmanes.caffeine.cache.simulator.admission.tinyCache;

import java.util.Random;

/**
 * This is the TinyCache sketch that is based on TinySet and TinyTable. It is adopted for fast
 * operation and bounded memory footprint. When a set is full, a victim is selected at random from
 * the full set. (basically some sort of random removal cache).
 *
 * @author gilga1983@gmail.com (Gil Einziger)
 */
public final class TinyCacheSketch {
	protected int nrItems;

	public final long chainIndex[];
	public final long[] isLast;
	private final HashFunctionParser hashFunc;
	private final int itemsPerSet;
	private final byte[] cache;
	private final Random rnd= new Random();
	public TinyCacheSketch(int nrSets, int itemsPerSet) {
		chainIndex = new long[nrSets];
		isLast = new long[nrSets];
		hashFunc = new HashFunctionParser(nrSets);
		this.itemsPerSet = itemsPerSet;
		cache = new byte[nrSets * itemsPerSet];
	}

	public int countItem(long item) {
		hashFunc.createHash(item);
		int $ = 0;
		if (!TinySetIndexing.chainExist(chainIndex[hashFunc.fpaux.set], hashFunc.fpaux.chainId)) {
			return 0;
		}
		TinySetIndexing.getChain(hashFunc.fpaux, chainIndex, isLast);
		int offset = this.itemsPerSet * hashFunc.fpaux.set;
		TinySetIndexing.ChainStart += offset;
		TinySetIndexing.ChainEnd += offset;

		// Gil : I think some of these tests are, I till carefully examine this function when I have
		// time. As far as I understand it is working right now.
		while (TinySetIndexing.ChainStart <= TinySetIndexing.ChainEnd) {
			try {
				if (TinySetIndexing.ChainStart == this.cache.length) {
					break;
				}
				$ += (cache[TinySetIndexing.ChainStart % cache.length] == hashFunc.fpaux.fingerprint)
						? 1L
								: 0L;
				TinySetIndexing.ChainStart++;

			} catch (Exception e) {
				System.out.println(" length: " + cache.length + " Access: " + TinySetIndexing.ChainStart);
				// e.printStackTrace();
			}
		}
		return $;
	}

	/**
	 * Implementing add and remove together in one function, means that less items are shifted.
	 * (reduction of 3 times from trivial implementation).
	 *
	 * @param fpaux
	 * @param victim
	 * @param bucketStart
	 * @return
	 */
	private int replace(HashedItem fpaux, byte victim, int bucketStart) {
		byte chainId = fpaux.chainId;
		fpaux.chainId = victim;

		int removedOffset = TinySetIndexing.getChainEnd(fpaux, chainIndex, isLast);
		this.cache[bucketStart + removedOffset] = 0;

		TinySetIndexing.removeItem(fpaux, chainIndex, isLast);
		fpaux.chainId = chainId;
		int idxToAdd = TinySetIndexing.addItem(fpaux, chainIndex, isLast);

		if (removedOffset < idxToAdd) {
			replaceBackwards(bucketStart, idxToAdd, fpaux.fingerprint);
		} else {
			replaceForward(idxToAdd, fpaux.fingerprint, bucketStart);
		}
		return removedOffset;
	}

	public void addItem(long item) {
		nrItems++;
		hashFunc.createHash(item);
		int bucketStart = this.itemsPerSet * hashFunc.fpaux.set;
		if (cache[bucketStart + this.itemsPerSet - 1] != 0) {
			selectVictim(bucketStart);
			return;
		}

		int idxToAdd = TinySetIndexing.addItem(hashFunc.fpaux, chainIndex, isLast);
		this.replaceForward(idxToAdd, hashFunc.fpaux.fingerprint, bucketStart);
	}

	private void selectVictim(int bucketStart) {
		byte victim = (byte) rnd.nextInt(64);
		//		((this.nrItems) & 63);
		HashedItem hashedItem = new HashedItem(hashFunc.fpaux.set, (byte) 0, hashFunc.fpaux.fingerprint);

		for(int i=0; i<64;i++){
			if(!TinySetIndexing.chainExist(chainIndex[hashedItem.set], i))
				continue;
			hashedItem.chainId = (byte) i;
			TinySetIndexing.getChain(hashedItem, chainIndex, isLast);
			if(victim>= TinySetIndexing.ChainStart && victim<= TinySetIndexing.ChainEnd)
			{
				replace(hashFunc.fpaux, (byte) i, bucketStart);
				return;
			}
			    	 
		}
			

	}

	protected void replaceBackwards(int start, final int maxToShift, byte value) {
		start += maxToShift;
		byte $;
		do {
			$ = this.cache[start];
			this.cache[start] = value;
			value = $;
			start--;
		} while (value != 0);
	}

	protected void replaceForward(final int idx, byte value, int start) {
		start += idx;

		do {
			byte $ = this.cache[start];
			this.cache[start] = value;
			value = $;
			start++;
		} while (value != 0);
	}
}
