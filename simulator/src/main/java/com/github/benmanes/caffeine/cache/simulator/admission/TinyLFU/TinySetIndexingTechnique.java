package com.github.benmanes.caffeine.cache.simulator.admission.TinyLFU;

public class TinySetIndexingTechnique {
	
	
	// for performance - for functions that need to know both the start and the end of the chain. 
	public static int ChainStart; 
	public static int ChainEnd; 
	
	public static int getChainStart(FingerPrintAuxByteFP fpaux,long[] I0, long[] IStar)
	{
		int requiredChainNumber = rank(I0[fpaux.bucketId], fpaux.chainId);
		int currentChainNumber = rank(IStar[fpaux.bucketId], requiredChainNumber);
		int currentOffset = requiredChainNumber;
		long tempIStar = IStar[fpaux.bucketId]>>>requiredChainNumber;
		while(currentChainNumber<requiredChainNumber)
		{
			currentChainNumber +=tempIStar&1l;
			currentOffset++;
			tempIStar>>>=1;
		}
		return currentOffset; 
	}
	public static int rank(long Istar, int idx)
	{
		return Long.bitCount(Istar&((1l<<idx)-1));
	}

	
	public static int getChainStart(FingerPrintAuxByteFP FingerPrintAuxByteFP, long[] index) {
		int I0Offset = FingerPrintAuxByteFP.bucketId<<1;
		int IStarOffset = I0Offset+1;
		int requiredChainNumber = rank(index[I0Offset], FingerPrintAuxByteFP.chainId);
		int currentChainNumber = rank(index[IStarOffset], requiredChainNumber);
		int currentOffset = requiredChainNumber;
		long tempIStar = index[IStarOffset]>>>requiredChainNumber;
		while(currentChainNumber<requiredChainNumber)
		{
			currentChainNumber +=tempIStar&1l;
			currentOffset++;
			tempIStar>>>=1;
		}
		return currentOffset; 
	}
	
	
	public static int getChainEnd(FingerPrintAuxByteFP fpaux,long[] I0, long[] IStar)
	{
		int requiredChainNumber = rank(I0[fpaux.bucketId], fpaux.chainId);
		int currentChainNumber = rank(IStar[fpaux.bucketId], requiredChainNumber);
		int currentOffset = requiredChainNumber;
		
		long tempIStar = IStar[fpaux.bucketId]>>>requiredChainNumber;
		while(currentChainNumber<requiredChainNumber)
		{
			currentChainNumber +=tempIStar&1l;
			currentOffset++;
			tempIStar>>>=1;

		}
		while((tempIStar&1l)==0)
		{
			currentOffset++;
			tempIStar>>>=1;

		}
		return currentOffset; 
	}
	
	public static int getChainEnd(FingerPrintAuxByteFP FingerPrintAuxByteFP, long[] index) {
		int I0Offset = FingerPrintAuxByteFP.bucketId<<1;
		int IStarOffset = I0Offset+1;
		
		int requiredChainNumber = rank(index[I0Offset], FingerPrintAuxByteFP.chainId);
		int currentChainNumber = rank(index[IStarOffset], requiredChainNumber);
		int currentOffset = requiredChainNumber;
		
		long tempIStar = index[IStarOffset]>>>requiredChainNumber;
		while(currentChainNumber<requiredChainNumber)
		{
			currentChainNumber +=tempIStar&1l;
			currentOffset++;
			tempIStar>>>=1;

		}
		while((tempIStar&1l)==0)
		{
			currentOffset++;
			tempIStar>>>=1;

		}
		return currentOffset; 
	}


	
	
	public static int getChain(FingerPrintAuxByteFP fpaux, long[] index) {
		int BaseOffset = fpaux.bucketId<<1;
		int ISarrOffset = BaseOffset+1;
		int requiredChainNumber = rank(index[BaseOffset], fpaux.chainId);
		int currentChainNumber = rank(index[ISarrOffset], requiredChainNumber);
		int currentOffset = requiredChainNumber;
		
		long tempIStar = index[ISarrOffset]>>>requiredChainNumber;
		while(currentChainNumber<requiredChainNumber)
		{
			currentChainNumber +=tempIStar&1l;
			currentOffset++;
			tempIStar>>>=1;

		}
		TinySetIndexingTechnique.ChainStart = currentOffset;
		while((tempIStar&1l)==0)
		{
			currentOffset++;
			tempIStar>>>=1;

		}
		TinySetIndexingTechnique.ChainEnd = currentOffset;
		return currentOffset; 		
	}
	
	public static int getChain(FingerPrintAuxByteFP fpaux,long[] I0, long[] IStar)
	{
		int requiredChainNumber = rank(I0[fpaux.bucketId], fpaux.chainId);
		int currentChainNumber = rank(IStar[fpaux.bucketId], requiredChainNumber);
		int currentOffset = requiredChainNumber;
		
		long tempIStar = IStar[fpaux.bucketId]>>>requiredChainNumber;
		while(currentChainNumber<requiredChainNumber)
		{
			currentChainNumber +=tempIStar&1l;
			currentOffset++;
			tempIStar>>>=1;

		}
		TinySetIndexingTechnique.ChainStart = currentOffset;
		while((tempIStar&1l)==0)
		{
			currentOffset++;
			tempIStar>>>=1;

		}
		TinySetIndexingTechnique.ChainEnd = currentOffset;
		return currentOffset; 
	}
	
	public static boolean chainExist(long I0, int chainId)
	{
//		long mask = ;
		return (I0|(1l<<chainId))==I0;
	}
	
	
	public static int addItem(FingerPrintAuxByteFP fpaux, long[] I0, long[] IStar) {
		
		int offset  = getChainStart(fpaux,I0,IStar);
		long mask = 1l<<fpaux.chainId;
		IStar[fpaux.bucketId] = extendZero(IStar[fpaux.bucketId],offset);

		//if the item is new... 
		if((mask|I0[fpaux.bucketId]) != I0[fpaux.bucketId])
		{
			// add new chain to IO.
			I0[fpaux.bucketId]|=mask;
			// mark item as last in IStar.
			IStar[fpaux.bucketId]|=(1l<<offset);
//			//= extendOne(IStar[fpaux.bucketId], offset);
//			return offset;
		}
		
		return offset;
	}
	public static int addItem(FingerPrintAuxByteFP fpaux, long[] index) {
		int BaseOffset = fpaux.bucketId<<1;
		int ISarrOffset = BaseOffset+1;
		int offset  = getChainStart(fpaux,index);
		long mask = 1l<<fpaux.chainId;
		index[ISarrOffset] = extendZero(index[ISarrOffset],offset);

		//if the item is new... 
		if((mask|index[BaseOffset]) != index[BaseOffset])
		{
			// add new chain to IO.
			index[BaseOffset]|=mask;
			// mark item as last in IStar.
			index[ISarrOffset]|=(1l<<offset);
//			//= extendOne(IStar[fpaux.bucketId], offset);
//			return offset;
		}
		
		return offset;
	}
	
//	private static long extendOne(final long IStar, final int offset) {
//		return extendZero(IStar,offset)|(1l<<offset);
//	
//	}
	private static long extendZero(final long IStar, final int offset) {
		long constantPartMask = (1l<<offset)-1;
		return (IStar&constantPartMask)|((IStar<<1l)& (~(constantPartMask))&(~(1l<<offset)));
	
	}
	
	private static long shrinkOffset(long IStar, int offset) {
		long conMask = ((1l<<offset) -1);
		return  (IStar&conMask)|(((~conMask)&IStar)>>>1);
	}
	public static void RemoveItem(FingerPrintAuxByteFP fpaux, long[] I0,long[] IStar)
	{
		int chainStart = getChainStart(fpaux,I0,IStar);
		// avoid an if command: either update I0 to the new state or keep it the way it is. 
		I0[fpaux.bucketId] = (IStar[fpaux.bucketId]&(1l<<chainStart))!=0l?I0[fpaux.bucketId]&~(1l<<fpaux.chainId):I0[fpaux.bucketId];
		// update IStar.
		IStar[fpaux.bucketId] = shrinkOffset(IStar[fpaux.bucketId],chainStart);

	}

	public static void RemoveItem(FingerPrintAuxByteFP fpaux, long[] index) {
		
		int BaseOffset = fpaux.bucketId<<1;
		int ISarrOffset = BaseOffset+1;

		int chainStart = getChainStart(fpaux,index);
		// avoid an if command: either update I0 to the new state or keep it the way it is. 
		index[BaseOffset] = (index[ISarrOffset]&(1l<<chainStart))!=0l?index[BaseOffset]&~(1l<<fpaux.chainId):index[BaseOffset];
		// update IStar.
		index[ISarrOffset] = shrinkOffset(index[ISarrOffset],chainStart);
		
	}




	



}
