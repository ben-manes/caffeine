package com.github.benmanes.caffeine.cache.simulator.admission.tinyCache;

/**
 *  This is the TinyCache sketch that is based on TinySet and TinyTable. 
 *  It is adopted for fast operation and bounded memory footprint. When a set is full, a victim 
 *  is selected at random from the full set. (basically some sort of random removal cache). 
 * @author  gilga1983@gmail.com (Gil Einziger)
 *
 */
public class TinyCacheSketch {
	protected int nrItems;

	public final long chainIndex[];
	public final long[] isLast;
	private final HashFunctionParser hashFunc;
	private final int itemsPerSet;
	private final byte[] cache;

	public TinyCacheSketch(int nrSets, int itemsPerSet)
	{
		chainIndex = new long[nrSets];
		isLast = new long[nrSets];
		hashFunc = new HashFunctionParser(nrSets);
		this.itemsPerSet = itemsPerSet;
		cache = new byte[nrSets*itemsPerSet];
	}
	public int countItem(long item)
	{
		hashFunc.createHash(item);
		int $ =0;
		if(!TinySetIndexing.chainExist(chainIndex[hashFunc.fpaux.set], hashFunc.fpaux.chainId))
			return 0;
		TinySetIndexing.getChain(hashFunc.fpaux, chainIndex, isLast);
		int offset = this.itemsPerSet*hashFunc.fpaux.set;
		TinySetIndexing.ChainStart+=offset;
		TinySetIndexing.ChainEnd+=offset;
		
		// Gil : I think some of these tests are, I till carefully examine this function when I have time. 
		// As far as I understand it is working right now. 
		while(TinySetIndexing.ChainStart<=TinySetIndexing.ChainEnd)
		{
			try{
				if(TinySetIndexing.ChainStart ==this.cache.length)
				{
					break;
				}
				$ += (cache[TinySetIndexing.ChainStart%cache.length]== hashFunc.fpaux.fingerprint)?1L:0l;
				TinySetIndexing.ChainStart++;
				
			}
			catch( Exception e){
				System.out.println(" length: " + cache.length  + " Access: "+ TinySetIndexing.ChainStart);
//				e.printStackTrace();
			}
		}
		return $;
	}
	/** 
	 * Implementing add and remove together in one function, means that less items are shifted. (reduction of 3 times from 
	 * trivial implementation). 
	 * @param fpaux
	 * @param victim
	 * @param bucketStart
	 * @return
	 */
	private int replace(HashedItem fpaux, byte victim,int bucketStart)
	{	
		byte chainId = fpaux.chainId;
		fpaux.chainId = victim;

		int removedOffset = TinySetIndexing.getChainEnd(fpaux,chainIndex,isLast);
		this.cache[bucketStart+removedOffset]=0;

		TinySetIndexing.removeItem(fpaux, chainIndex,isLast);
		fpaux.chainId = chainId;
		int idxToAdd =  TinySetIndexing.addItem(fpaux,chainIndex,isLast);
		
		
		if(removedOffset<idxToAdd){
			this.replaceBackwards(bucketStart,idxToAdd,fpaux.fingerprint);
		}
		else
		{

			this.replaceForward(idxToAdd,fpaux.fingerprint,bucketStart);
		}
		return removedOffset;



	}
	public void addItem(long item)
	{

		this.nrItems++;
		hashFunc.createHash(item);
		int bucketStart = this.itemsPerSet*hashFunc.fpaux.set;
		if(this.cache[bucketStart+ this.itemsPerSet-1]!= 0)
		{
			byte victim = (byte) (this.nrItems&63);
			long mask = (1l<<victim);

			while((chainIndex[hashFunc.fpaux.set]&mask) ==0L)
			{
				victim++;
				victim&=63;
				mask = (1L<<victim);


			}
			replace(hashFunc.fpaux,victim,bucketStart);
			return;
		}

		int idxToAdd = TinySetIndexing.addItem(hashFunc.fpaux, chainIndex, isLast);
		this.replaceForward(idxToAdd, hashFunc.fpaux.fingerprint,bucketStart);

		return;
	}



	protected void replaceBackwards( int start, final int maxToShift, byte value) {
		start += maxToShift;
		byte $;
		do
		{
			$= this.cache[start];
			this.cache[start]=value;
			value = $;
			start--;
		}
		while(value!=0l );

	}
	protected void replaceForward(final int idx,  byte value, int start) {
		start  += idx;

		do
		{
			byte $= this.cache[start];
			this.cache[start]=value;
			value = $;
			start++;
		}
		while(value!=0 );
		return;
	}

}