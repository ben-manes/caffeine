package il.technion.ewolf.BloomFilters.Tools.TinyCache;

import il.technion.ewolf.BloomFilters.Tools.HashFunctions.FingerPrintAux;
import il.technion.ewolf.BloomFilters.Tools.HashFunctions.GreenHashMaker;
import il.technion.ewolf.BloomFilters.Tools.HashTables.NewIndexingTechnique;

public class NewTinyCache {
	protected int nrItems;

	public final long L[];
	public final long[] IStar;
	private final GreenHashMaker hashFunc;
	private final int itemsPerSet;

	private final long[] cache;

	public NewTinyCache(int nrSets, int itemsPerSet)
	{
		L = new long[nrSets];
		IStar = new long[nrSets];
		hashFunc = new GreenHashMaker(10, nrSets, 64);
		this.itemsPerSet = itemsPerSet;
		cache = new long[nrSets*itemsPerSet];
	}
	public int countItem(long item)
	{
		hashFunc.createHash(item);
		int $ =0;
		if(!NewIndexingTechnique.chainExist(L[hashFunc.fpaux.bucketId], hashFunc.fpaux.chainId))
			return 0;
		NewIndexingTechnique.getChain(hashFunc.fpaux, L, IStar);
		int offset = this.itemsPerSet*hashFunc.fpaux.bucketId;
		NewIndexingTechnique.chainStart+=offset;
		NewIndexingTechnique.chainEnd+=offset;

		
		while(NewIndexingTechnique.chainStart<=NewIndexingTechnique.chainEnd)
		{
			$ += (cache[NewIndexingTechnique.chainStart++]== hashFunc.fpaux.fingerprint)?1l:0l;
		}
		return $;
	}
	public void PrintBucket(int bucketId)
	{
		for(int i =0; i<63; i++)
		{
			if((L[bucketId]&(1l<<i)) !=0)
			{
				System.out.print("Chain: "+i + " ");

				//int initial = NewIndexingTechnique.getChainStart(new FingerPrintAux(bucketId, i, 8383), L, IStar);
				//int end = NewIndexingTechnique.getChainEnd(new FingerPrintAux(bucketId, i, 8383), L, IStar);
				//for(int j =initial; j<=end; j++)
				{
				//	System.out.print(this.cache[bucketId*this.itemsPerSet + j] + " ");
				}
				
				//System.out.println("--- start: " + initial + " End "+ end);

			}

		}
	}
	public void addItem(long item)
	{
		
		this.nrItems++;
		hashFunc.createHash(item);
		int bucketStart = this.itemsPerSet*hashFunc.fpaux.bucketId;
		// Refresh items list for the rest of the play. 
		//			RankIndexHashing.getItemsPerLevelUpTo64New(L[bucketId],IStar[bucketId],offsets);
		if(this.cache[bucketStart+ this.itemsPerSet-1]!= 0l)
		{
//			if(true)
//				throw new RuntimeException("do not work");
			int victim = this.nrItems&63;
			long mask = (1l<<victim);

			while((L[hashFunc.fpaux.bucketId]&mask) ==0l)
			{
//				mask = Long.rotateLeft(mask,1);
				victim++;
				victim&=63;
				mask = (1l<<victim);


			}
//			System.out.println("Updating: "+ this.hashFunc.fpaux.bucketId + " removing from chain: " + victim + " adding to chain: "+ this.hashFunc.fpaux.chainId);
			//replace(hashFunc.fpaux,victim,bucketStart);
			return;
		}

		int idxToAdd = NewIndexingTechnique.addItem(hashFunc.fpaux, L, IStar);
		this.PutAndPush(idxToAdd, hashFunc.fpaux.fingerprint,bucketStart);

		return;
	}


/*
	private int replace(FingerPrintAux fpaux, int victim,int bucketStart)
	{	
		int chainId = fpaux.chainId;
		fpaux.chainId = victim;

		//int removedOffset = NewIndexingTechnique.getChainEnd(fpaux, L , IStar);

//		this.FastReplace(bucketStart, removedOffset, 0l);
		//this.cache[bucketStart+removedOffset]=0l;

		NewIndexingTechnique.removeItem(fpaux, L, IStar);
		fpaux.chainId = chainId;
		int idxToAdd =  NewIndexingTechnique.addItem(fpaux, L, IStar);
		//if(removedOffset<idxToAdd){
			this.replaceBackwards(bucketStart,idxToAdd,fpaux.fingerprint);
		//}
		//else
		{
			this.PutAndPush(idxToAdd,fpaux.fingerprint,bucketStart);
		}
		//return removedOffset;



	}
	*/
	public void FastPut(int bucketStart,int idx,  long value) {
		int start = bucketStart + idx;
		this.cache[start]=value;

	}

	public long FastReplace(int bucketStart, int idx,long value)
	{
		int start = bucketStart + idx;
		long $ = this.cache[start];
		this.cache[start]=value;
		return $;
		//return super.replaceBits(start, start + this.itemSize,value);

	}

	protected void replaceBackwards( int start, final int maxToShift, long value) {
		start += maxToShift;
		long $;
		do
		{
			 $= this.cache[start];
			this.cache[start]=value;
			value = $;
			start--;
		}
		while(value!=0l );

	}
	protected void PutAndPush(final int idx,  long value, int start) {
		 start  += idx;
		long $;
		do
		{
			 $= this.cache[start];
			this.cache[start]=value;
			value = $;

			start++;
		}
		while(value!=0l );
		return;
	}
	
}