package il.technion.ewolf.BloomFilters.Tools.TinyCache;

import il.technion.ewolf.BloomFilters.Tools.HashFunctions.FingerPrintAux;
import il.technion.ewolf.BloomFilters.Tools.HashFunctions.GreenHashMaker;
import il.technion.ewolf.BloomFilters.Tools.HashTables.RankIndexHashing;

public class TinyCache {
	protected int nrItems;

	public final long L[];
	public final long[] IStar;
	private final GreenHashMaker hashFunc;
	private final int itemsPerSet;

	// prevent generating garbage... re use same temporary variables. 
	private final byte[] offsets;
	private final byte[] chain;
	private final long[] cache;

	public TinyCache(int nrSets, int itemsPerSet)
	{
		L = new long[nrSets];
		IStar = new long[nrSets];
		hashFunc = new GreenHashMaker(10, nrSets, 64);
		this.itemsPerSet = itemsPerSet;
		offsets = new byte[64];
		chain = new byte[64];
		cache = new long[nrSets*itemsPerSet];
	}
	public int countItem(long item)
	{
		hashFunc.createHash(item);
		//		FingerPrintAux fpaux = 
		int $ =0;

		RankIndexHashing.getChainAndUpdateOffsets(hashFunc.fpaux, L, IStar,this.offsets,chain);

		int bucketStart = this.itemsPerSet*hashFunc.fpaux.bucketId;

		int i =0;

		while(chain[i]>=0)
		{
			$ += cache[bucketStart+chain[i++]]== hashFunc.fpaux.fingerprint?1:0;
		}
		return $;
	}

	public void addItem(long item)
	{
		this.nrItems++;
		FingerPrintAux fpaux = hashFunc.createHash(item);
		int bucketStart = this.itemsPerSet*fpaux.bucketId;
		// Refresh items list for the rest of the play. 
		//			RankIndexHashing.getItemsPerLevelUpTo64New(L[bucketId],IStar[bucketId],offsets);
		if(this.itemsPerSet <= this.getNrItems(fpaux.bucketId))
		{
			int victim = this.nrItems&63;
			long mask = (1l<<victim);

			while((L[fpaux.bucketId]&mask) ==0l)
			{
				victim++;
				victim&=63;
				mask = (1l<<victim);


			}

			replace(fpaux,victim,bucketStart);
			return;
		}

		RankIndexHashing.getItemsPerLevelUpTo64New(L,IStar,offsets,fpaux.bucketId);

		int idxToAdd = RankIndexHashing.addItem(fpaux, L, IStar,this.offsets,this.chain);
		//		System.out.println(idxToAdd);
		this.PutAndPush(idxToAdd, fpaux.fingerprint,bucketStart);

		return;
	}


	public int getNrItems(int bucketId)
	{
		return Long.bitCount(this.L[bucketId]) + Long.bitCount(this.IStar[bucketId]);
	}



	private int replace(FingerPrintAux fpaux, int victim,int bucketStart)
	{	
		int chainSize = RankIndexHashing.getChainAndUpdateOffsets(fpaux, L, IStar,this.offsets,this.chain,victim);

		int removedOffset = chain[0];
		int lastOffset = chain[chainSize];

		long lastItem = this.FastReplace(bucketStart, lastOffset, 0l);
		if(lastOffset!=removedOffset)
			this.FastPut(bucketStart, removedOffset, lastItem);
		RankIndexHashing.RemoveItem(victim, L, IStar, fpaux.bucketId,this.offsets,chain,chainSize);
		this.offsets[chainSize]--;
		int idxToAdd =  RankIndexHashing.addItem(fpaux, L, IStar,this.offsets,this.chain);
		if(lastOffset<idxToAdd){
			this.replaceBackwards(bucketStart,idxToAdd,fpaux.fingerprint);
		}
		else
		{
			this.PutAndPush(idxToAdd,fpaux.fingerprint,bucketStart);
		}
		return lastOffset;



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

	protected void replaceBackwards(final int bucketStart, final int maxToShift, long value) {
		int start =bucketStart+ maxToShift;
		do
		{
			value = this.FastReplace(start, 0, value);
			start--;
		}
		while(value!=0l );

	}
	protected void PutAndPush(int idx,  long value, int bucketStart) {
		int start = bucketStart + idx;
		do
		{
			value = this.FastReplace(start, 0, value);
			start++;
		}
		while(value!=0l );
		return;
	}
}