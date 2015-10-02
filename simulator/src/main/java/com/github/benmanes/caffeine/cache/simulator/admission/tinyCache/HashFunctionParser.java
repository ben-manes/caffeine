package com.github.benmanes.caffeine.cache.simulator.admission.tinyCache;

/**
 * This is a hash function and parser tp simplify parsing the hash value, it split it to . 
 * This class provide hash utilities, and parse the items. 
 * @author gilga1983@gmail.com (Gil Einziger)
 *
 */
public class HashFunctionParser {

	//currently chain is bounded to be 64.
	private final int fpSize=8; // this implementation assumes byte. 
	private final byte fpMask = (byte) 255; //(all bits in byte are 1, (logical value of -1));
	private final long chainMask=63l; // (6 first bit are set to 1). 
	private final int nrSets; 
	public HashedItem fpaux; // used just to avoid allocating new memory as a return value. 
	private final static long Seed64 =0xe17a1465;
	private final static long m = 0xc6a4a7935bd1e995L;
	private final  static int r = 47;

	public HashFunctionParser(int nrsets)
	{
		
		this.nrSets =nrsets;
		fpaux = new HashedItem(fpMask,fpMask,fpMask);
	}



	public HashedItem createHash(Object item)
	{
		return createHash(item.hashCode());
	}


	public HashedItem createHash(long item) {
		
		
		long h =  (Seed64) ^ (m);
		item *= m;
		item ^= item >>> r;
		item *= m;

		h ^= item;
		h *= m;
		
		fpaux.fingerprint = (byte) (h&fpMask);
		// the next line is a dirty fix as I do not want the value of 0 as a fingerprint.
		// It can be eliminated if we want very short fingerprints. 
		fpaux.fingerprint = (fpaux.fingerprint==0l)?1:fpaux.fingerprint;  
		h>>>=fpSize;
		fpaux.chainId = (byte) (h&chainMask);
		h>>>=6;
		fpaux.set =  (int) ((h&Long.MAX_VALUE)%nrSets);

		return fpaux;

	}



}
