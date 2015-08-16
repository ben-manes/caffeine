package com.github.benmanes.caffeine.cache.simulator.admission.TinyLFU;

import com.clearspring.analytics.stream.frequency.IFrequency;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;

public class TinyCacheAdmission implements Admittor,IFrequency {
	protected int nrItems;

	public final long[] index;
	private final HashFunction hashFunc;
	private final int itemsPerSet;

	private final byte[] cache;

	public TinyCacheAdmission(int nrSets, int itemsPerSet)
	{
		index = new long[nrSets*2];
		hashFunc = new HashFunction(nrSets, 64);
		this.itemsPerSet = itemsPerSet;
		cache = new byte[nrSets*(itemsPerSet)];
	}
	public int countItem(long item)
	{
		hashFunc.createHash(item);
		int $ =0;
		if(!TinySetIndexingTechnique.chainExist(index[hashFunc.fpaux.bucketId<<1], hashFunc.fpaux.chainId))
			return 0;
		TinySetIndexingTechnique.getChain(hashFunc.fpaux, index);
		int offset = this.itemsPerSet*hashFunc.fpaux.bucketId;
		TinySetIndexingTechnique.ChainStart+=offset;
		TinySetIndexingTechnique.ChainEnd+=offset;


		while(TinySetIndexingTechnique.ChainStart<TinySetIndexingTechnique.ChainEnd)
		{
			$ += (cache[TinySetIndexingTechnique.ChainStart++]== hashFunc.fpaux.fingerprint)?1l:0l;
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

				int initial = TinySetIndexingTechnique.getChainStart(new FingerPrintAuxByteFP(bucketId, (byte) i, (byte) 333), index);
				int end = TinySetIndexingTechnique.getChainEnd(new FingerPrintAuxByteFP(bucketId, (byte)i, (byte) 8383), index);
				for(int j =initial; j<=end; j++)
				{
					System.out.print(this.cache[bucketId*this.itemsPerSet + j] + " ");
				}

				System.out.println("--- start: " + initial + " End "+ end);

			}

		}
	}
	public void addItem(String item)
	{

		this.nrItems++;
		hashFunc.createHash(item);
		addItemOnceHashReady();
	}
	public void addItem(long item)
	{

		this.nrItems++;
		hashFunc.createHash(item);
		addItemOnceHashReady();
	}
	private void addItemOnceHashReady() {
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
		int idxToAdd = TinySetIndexingTechnique.addItem(hashFunc.fpaux, index);
		this.PutAndPush(idxToAdd, hashFunc.fpaux.fingerprint,bucketStart);

		return;
	}



	private int replace(FingerPrintAuxByteFP fpaux, byte victim,int bucketStart)
	{	
		byte chainId = fpaux.chainId;
		fpaux.chainId = victim;

		int removedOffset = TinySetIndexingTechnique.getChainEnd(fpaux,index);
		this.cache[bucketStart+removedOffset]=0;

		TinySetIndexingTechnique.RemoveItem(fpaux, index);
		fpaux.chainId = chainId;
		int idxToAdd =  TinySetIndexingTechnique.addItem(fpaux,index);
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
	@Override
	public void record(Object key) {
		this.addItem(key.hashCode());

	}
	@Override
	public boolean admit(Object candidateKey, Object victimKey) {
		int candidateScore = this.countItem(candidateKey.hashCode());
		int victimScore = this.countItem(victimKey.hashCode());
		return (victimScore<= candidateScore);

	}
	@Override
	public void add(long item, long count) {
		try{
			this.addItem(item);
		}
		catch(Exception E)
		{
			E.printStackTrace();

		}
	}
	@Override
	public void add(String item, long count) {
		try{
			this.addItem(item.hashCode());
		}
		catch(Exception E)
		{
			E.printStackTrace();

		}


	}
	@Override
	public long estimateCount(long item) {
		try{
			return this.countItem(item);
		}
		catch(Exception E)
		{
			E.printStackTrace();

			return 0;
		}
		//return this.countItem(item);	
	}
	@Override
	public long estimateCount(String item) {
		try{
			return this.countItem(item.hashCode());
		}
		catch(Exception E)
		{
			E.printStackTrace();
			return 0;
		}
//		return this.countItem(item.hashCode());
	}
	@Override
	public long size() {
		return this.nrItems;
		//		return 0;
	}

}