package com.github.benmanes.caffeine.cache.simulator.admission.TinyLFU;



public class HashFunction {

	// used as return value - to avoid dynamic memory allocation. 
	public FingerPrintAuxByteFP fpaux;
	//currently chain is bounded to be 64. 
	private final int fpSize;
	private final byte fpMask; 
	private final long chainMask=63l; 
	private final int bucketRange;
	private final static long Seed64 =0xe17a1465;
	private final static long m = 0xc6a4a7935bd1e995L;
	private final  static int r = 47;

	public HashFunction(int bucketrange, int chainrange)
	{
		this.fpSize = 8; /// For efficiency we use 8 bit fingerprints. 
		fpMask = (byte) 255;// 8 bits set to 1. 
		this.bucketRange =bucketrange;
		// allocate just once so that we do not generate garbadge. 
		fpaux = new FingerPrintAuxByteFP(fpMask,fpMask,fpMask); 
	}


	// can accept stings
	public FingerPrintAuxByteFP createHash(String item)
	{
		final byte[] data = item.getBytes();
		long hash =  MinorImprovedMurmurHash.hash64(data, data.length);
		fpaux.fingerprint = (byte)(hash&fpMask);
		if(fpaux.fingerprint ==0l)
		{
			fpaux.fingerprint++;
		}



		hash>>>=fpSize;
		fpaux.chainId = (byte) (hash&chainMask);
		hash>>>=6;
		fpaux.bucketId =  (int) ((hash&Long.MAX_VALUE)%bucketRange);

		return fpaux;
	}


	public FingerPrintAuxByteFP createHash(long item) {
		
		
		long h =  (Seed64) ^ (m);
		item *= m;
		item ^= item >>> r;
		item *= m;

		h ^= item;
		h *= m;
		
		fpaux.fingerprint = (byte) (h&fpMask);
		fpaux.fingerprint = (fpaux.fingerprint==0l)?1:fpaux.fingerprint; 
		



		h>>>=fpSize;
		fpaux.chainId = (byte) (h&chainMask);
		h>>>=6;
		fpaux.bucketId =  (int) ((h&Long.MAX_VALUE)%bucketRange);

		return fpaux;

	}



}
