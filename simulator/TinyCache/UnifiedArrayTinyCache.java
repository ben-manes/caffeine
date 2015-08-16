package il.technion.ewolf.BloomFilters.Tools.TinyCache;

import il.technion.ewolf.BloomFilters.Tools.HashFunctions.FingerPrintAux;
import il.technion.ewolf.BloomFilters.Tools.HashFunctions.GreenHashMaker;
import il.technion.ewolf.BloomFilters.Tools.HashTables.UnifiedIndexingTechnique;

public class UnifiedArrayTinyCache {
	protected int nrItems;

//	public final long L[];
//	public final long[] index;
	private final GreenHashMaker hashFunc;
	private final int itemsPerSet;

	int bucketSize;
	private final long[] cache;

	public UnifiedArrayTinyCache(int nrSets, int itemsPerSet)
	{
//		L = new long[nrSets];
//		IStar = new long[nrSets];
//		index = new long[nrSets*2];
		hashFunc = new GreenHashMaker(10, nrSets, 64);
		this.bucketSize = itemsPerSet+2; 
		this.itemsPerSet = itemsPerSet;

		cache = new long[nrSets*(bucketSize)];
	}
	public int countItem(long item)
	{
		hashFunc.createHash(item);
		int $ =0;
		int offset = bucketSize*hashFunc.fpaux.bucketId;
		if(!UnifiedIndexingTechnique.chainExist(cache[offset], hashFunc.fpaux.chainId))
			return 0;
		UnifiedIndexingTechnique.getChain(hashFunc.fpaux, cache,offset);
		 offset  +=2;
		UnifiedIndexingTechnique.ChainStart+=offset;
		UnifiedIndexingTechnique.ChainEnd+=offset;

		
		while(UnifiedIndexingTechnique.ChainStart<=UnifiedIndexingTechnique.ChainEnd)
		{
			$ += (cache[UnifiedIndexingTechnique.ChainStart++]== hashFunc.fpaux.fingerprint)?1l:0l;
		}
		return $;
	}
	public void PrintBucket(int bucketId)
	{
		int offset = bucketId*this.bucketSize;
		for(int i =0; i<63; i++)
		{
			if((cache[offset]&(1l<<i)) !=0)
			{
				System.out.print("Chain: "+i + " ");

				int initial = UnifiedIndexingTechnique.getChainStart(new FingerPrintAux(bucketId, i, 8383), cache,offset);
				int end = UnifiedIndexingTechnique.getChainEnd(new FingerPrintAux(bucketId, i, 8383), cache,offset);
				for(int j =initial; j<=end; j++)
				{
					System.out.print(this.cache[bucketId*this.itemsPerSet + j] + " ");
				}
				
				System.out.println("--- start: " + initial + " End "+ end);

			}

		}
	}
	public void addItem(long item)
	{
		
		this.nrItems++;
		hashFunc.createHash(item);
		int offset = this.bucketSize*hashFunc.fpaux.bucketId;
		int bucketStart =offset +2;
		// Refresh items list for the rest of the play. 
		//			RankIndexHashing.getItemsPerLevelUpTo64New(L[bucketId],IStar[bucketId],offsets);
		if(this.cache[bucketStart+ this.itemsPerSet-1]!= 0l)
		{
//			if(true)
//				throw new RuntimeException("do not work");
			int victim = this.nrItems&63;
			long mask = (1l<<victim);

			while((cache[offset]&mask) ==0l)
			{
//				mask = Long.rotateLeft(mask,1);
				victim++;
				victim&=63;
				mask = (1l<<victim);


			}
//			System.out.println("Updating: "+ this.hashFunc.fpaux.bucketId + " removing from chain: " + victim + " adding to chain: "+ this.hashFunc.fpaux.chainId);
			replace(hashFunc.fpaux,victim,bucketStart);
			return;
		}

		int idxToAdd = UnifiedIndexingTechnique.addItem(hashFunc.fpaux, cache,offset);
		this.PutAndPush(idxToAdd, hashFunc.fpaux.fingerprint,bucketStart);

		return;
	}



	private int replace(FingerPrintAux fpaux, int victim,int bucketStart)
	{	
		int chainId = fpaux.chainId;
		fpaux.chainId = victim;
		int I0Offset = bucketStart-2;
		int removedOffset = UnifiedIndexingTechnique.getChainEnd(fpaux,cache,I0Offset);

//		this.FastReplace(bucketStart, removedOffset, 0l);
		this.cache[bucketStart+removedOffset]=0l;

		UnifiedIndexingTechnique.RemoveItem(fpaux, cache,I0Offset);
		fpaux.chainId = chainId;
		int idxToAdd =  UnifiedIndexingTechnique.addItem(fpaux,cache,I0Offset);
		if(removedOffset<idxToAdd){
			this.replaceBackwards(bucketStart,idxToAdd,fpaux.fingerprint);
		}
		else
		{
			this.PutAndPush(idxToAdd,fpaux.fingerprint,bucketStart);
		}
		return removedOffset;



	}
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