package il.technion.ewolf.BloomFilters.Tools.TinyCache;

import il.technion.ewolf.BloomFilters.Tools.HashFunctions.FingerPrintAux;
import il.technion.ewolf.BloomFilters.Tools.HashFunctions.GreenHashMaker;
import il.technion.ewolf.BloomFilters.Tools.HashTables.UnifiedIndexingTechnique;

public class UnifiedArrayTinyLFU {
	protected int nrItems;

	//	public final long L[];
	//	public final long[] index;
	private final GreenHashMaker hashFunc;
	private final int itemsPerSet;

	int bucketSize;
	private final long[] cache;
	// always one byte!- but with longs!
	public UnifiedArrayTinyLFU(int nrSets, int itemsPerSet)
	{
		//		L = new long[nrSets];
		//		IStar = new long[nrSets];
		//		index = new long[nrSets*2];
		hashFunc = new GreenHashMaker(8, nrSets, 64);
		this.bucketSize = itemsPerSet/8+2; 
		this.itemsPerSet = itemsPerSet;

		cache = new long[nrSets*(bucketSize)];
	}

	public long getFromCache(int initialOffset, int offsetInBucket)
	{
		int longOffset = offsetInBucket>>3;
		int byteInLong = offsetInBucket&7;
		long item = cache[initialOffset+longOffset];
		long mask = (255l)<<(8*byteInLong);
		return  (item&mask)>>>(8*byteInLong);
	}
	public long replace(int start, int idx,long itemToReplace)
	{
		start += idx>>3;
		idx = (idx&7)<<3;
		long item = cache[start];
		cache[start] =(cache[start]&(~((255l)<<(idx))))|(itemToReplace<<idx);
		return  (item)>>>(idx)&255l;
	}

	public long replaceMany(int startbucket, int idx,long itemToReplace)
	{
		do{
		int start = idx>>3 + startbucket;
		int idxb = (idx&7)<<3;

		long item = cache[start];
		cache[start] =(cache[start]&(~((255l)<<(idxb))))|(itemToReplace<<idxb);
		itemToReplace=  (item)>>>(idxb)&255l;
		idx++;
		}
		while(itemToReplace!=0l);
		return itemToReplace;

	}



	public long Shift8(int initialOffset, int offsetInBucket,long itemToReplace)
	{
		int longOffset = offsetInBucket>>3+initialOffset;
		//		long mask = (255l<<56);
		long retVal = (cache[longOffset])>>>56;
		cache[longOffset] = (cache[longOffset]<<8)|itemToReplace;
		return retVal;
	}
	public long Shift8Improved(int initialOffset, int offsetInBucket,long itemToReplace)
	{
		// word to access 
		int longOffset = offsetInBucket>>3;
		// byte in word to access
		int itemInLongOffset = offsetInBucket&7;
		// mask of word delete the last value. 
		long mask = (255l<<56);
		long retVal = (cache[initialOffset+longOffset]&mask)>>>56;

		//the constant part of the word,
		mask = ~(-1l<<(itemInLongOffset<<3));
		long word = cache[initialOffset+longOffset]&mask|(itemToReplace<<(itemInLongOffset<<3)); 
		// to include also the new item. 
		cache[initialOffset+longOffset] = word|(cache[initialOffset+longOffset]&(~mask)<<8);
		return retVal;
	}
	//	public long ShiftAlmostAllTheWay(int initialOffset, int offsetInBucket,long itemToReplace, int endOffset)
	//	{
	//		if(offsetInBucket>>3<=endOffset>>3)
	//		{
	//			itemToReplace = Shift8Improved(initialOffset,offsetInBucket,itemToReplace);
	//			offsetInBucket+=(8-offsetInBucket&7);
	//
	//		}
	//		return itemToReplace;
	//
	//	}


	public int countItem(long item)
	{
		hashFunc.createHash(item);
		int $ =0;
		int offset = bucketSize*hashFunc.fpaux.bucketId;
		if(!UnifiedIndexingTechnique.chainExist(cache[offset], hashFunc.fpaux.chainId))
			return 0;
		UnifiedIndexingTechnique.getChain(hashFunc.fpaux, cache,offset);
		offset  +=2;


		while(UnifiedIndexingTechnique.ChainStart<=UnifiedIndexingTechnique.ChainEnd)
		{
			$+= (this.getFromCache(offset, UnifiedIndexingTechnique.ChainStart++) ==  hashFunc.fpaux.fingerprint)?1l:0l;

			//			$ += (cache[UnifiedIndexingTechnique.ChainStart++]== hashFunc.fpaux.fingerprint)?1l:0l;
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
					System.out.print(this.getFromCache(bucketId*this.itemsPerSet, j) + " ");
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
		//		if(this.cache[bucketStart+ this.itemsPerSet-1]!= 0l)
		if(this.getFromCache(bucketStart, this.itemsPerSet-1)!=0l)
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
		this.PutAndPush(idxToAdd, hashFunc.fpaux.fingerprint,bucketStart,bucketStart);

		return;
	}



	private int replace(FingerPrintAux fpaux, int victim,int bucketStart)
	{	
		int chainId = fpaux.chainId;
		fpaux.chainId = victim;
		int I0Offset = bucketStart-2;
		int removedOffset = UnifiedIndexingTechnique.getChainEnd(fpaux,cache,I0Offset);

		//		this.FastReplace(bucketStart, removedOffset, 0l);
		this.replace(bucketStart, removedOffset, 0l);
		//		this.cache[bucketStart+removedOffset]=0l;

		UnifiedIndexingTechnique.RemoveItem(fpaux, cache,I0Offset);
		fpaux.chainId = chainId;
		int idxToAdd =  UnifiedIndexingTechnique.addItem(fpaux,cache,I0Offset);
		if(removedOffset<idxToAdd){
			//			System.out.println("Need To Shift: "+ (idxToAdd -removedOffset));
			this.replaceBackwards(bucketStart,idxToAdd,fpaux.fingerprint,idxToAdd);
		}
		else
		{
			//			System.out.println("Need To Shift: "+ (removedOffset -idxToAdd));

			this.PutAndPush(idxToAdd,fpaux.fingerprint,bucketStart,idxToAdd);
		}
		return removedOffset;



	}





	protected void replaceBackwards( int start,  int idx, long value, int endOffset) {
		do
		{

			value = this.replace(start, idx--, value);

		}
		while(value!=0l );

	}
	protected void PutAndPush( int idx,  long value, int start,int endOffset) {
		do
		{

			value = this.replace(start, idx++, value);

		}
		while(value!=0l );
		return;
	}

}