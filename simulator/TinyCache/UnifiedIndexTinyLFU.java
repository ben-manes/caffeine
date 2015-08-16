package il.technion.ewolf.BloomFilters.Tools.TinyCache;

import il.technion.ewolf.BloomFilters.Tools.HashFunctions.FingerPrintAuxByteFP;
import il.technion.ewolf.BloomFilters.Tools.HashFunctions.GreenHashMakerByteFP;
import il.technion.ewolf.BloomFilters.Tools.HashTables.NewIndexingTechniqueByteFP;

public class UnifiedIndexTinyLFU {
	protected int nrItems;

	public final long[] index;
	private final GreenHashMakerByteFP hashFunc;
	private final int itemsPerSet;

	private final byte[] cache;

	public UnifiedIndexTinyLFU(int nrSets, int itemsPerSet)
	{
		index = new long[nrSets*2];
		hashFunc = new GreenHashMakerByteFP(nrSets, 64);
		this.itemsPerSet = itemsPerSet;
		cache = new byte[nrSets*(itemsPerSet)];
	}
	public int countItem(long item)
	{
		hashFunc.createHash(item);
		int $ =0;
		if(!NewIndexingTechniqueByteFP.chainExist(index[hashFunc.fpaux.bucketId<<1], hashFunc.fpaux.chainId))
			return 0;
		NewIndexingTechniqueByteFP.getChain(hashFunc.fpaux, index);
		int offset = this.itemsPerSet*hashFunc.fpaux.bucketId;
		NewIndexingTechniqueByteFP.ChainStart+=offset;
		NewIndexingTechniqueByteFP.ChainEnd+=offset;


		while(NewIndexingTechniqueByteFP.ChainStart<=NewIndexingTechniqueByteFP.ChainEnd)
		{
			$ += (cache[NewIndexingTechniqueByteFP.ChainStart++]== hashFunc.fpaux.fingerprint)?1l:0l;
		}
		return $;
	}
	public void PrintBucket(int bucketId)
	{
		for(int i =0; i<63; i++)
		{
			if((index[bucketId<<1]&(1l<<i)) !=0)
			{
				System.out.print("Chain: "+i + " ");

				int initial = NewIndexingTechniqueByteFP.getChainStart(new FingerPrintAuxByteFP(bucketId, (byte) i, (byte) 333), index);
				int end = NewIndexingTechniqueByteFP.getChainEnd(new FingerPrintAuxByteFP(bucketId, (byte)i, (byte) 8383), index);
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
		int bucketStart = this.itemsPerSet*hashFunc.fpaux.bucketId;
				
				
				
		if(this.cache[bucketStart+ this.itemsPerSet-1]!= 0l)
		{

			byte victim = (byte) (this.nrItems&63);
			int I0Offset = hashFunc.fpaux.bucketId<<1;
			while((index[I0Offset]&(1l<<victim)) ==0l)
			{
				victim++;
				victim&=63;
			}
			replace(hashFunc.fpaux,victim,bucketStart);
			return;
		}
		int idxToAdd = NewIndexingTechniqueByteFP.addItem(hashFunc.fpaux, index);
		this.PutAndPush(idxToAdd, hashFunc.fpaux.fingerprint,bucketStart);

		return;
	}



	private int replace(FingerPrintAuxByteFP fpaux, byte victim,int bucketStart)
	{	
		byte chainId = fpaux.chainId;
		fpaux.chainId = victim;

		int removedOffset = NewIndexingTechniqueByteFP.getChainEnd(fpaux,index);
		this.cache[bucketStart+removedOffset]=0;

		NewIndexingTechniqueByteFP.RemoveItem(fpaux, index);
		fpaux.chainId = chainId;
		int idxToAdd =  NewIndexingTechniqueByteFP.addItem(fpaux,index);
		if(removedOffset<idxToAdd){
			this.replaceBackwards(bucketStart,idxToAdd,fpaux.fingerprint);
		}
		else
		{

			this.PutAndPush(idxToAdd,fpaux.fingerprint,bucketStart);
		}
		return removedOffset;



	}




	protected void replaceBackwards( int start, final int maxToShift, byte value) {
		start += maxToShift;
		byte $;
		do
		{
			$= this.cache[start];
			this.cache[start]=value;
			value =  $;
			start--;
		}
		while(value!=0l );

	}
	protected void PutAndPush(final int idx,  byte value, int start) {
		start  += idx;
		byte $;
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