package com.github.benmanes.caffeine.cache.simulator.admission.tinyCache;

/**
 * An implementation of TinySet's indexing method. A method to index a succinct hash table that is only 2 bits from 
 * theoretical lower bound. This is only the indexing technique and it helps calculate offsets in array using two indexes. 
 * chainIndex - (set bit for non empty chain/unset for empty)
 * isLastIndex (set bit for last in chain/empty bit for not last in chain). 
 * Both indexes are assumed to be 64 bits, (longs) for efficiency and simplicity. 
 * The technique update the indexes upon addition/removal.
 * Paper link: http://www.cs.technion.ac.il/users/wwwb/cgi-bin/tr-get.cgi/2015/CS/CS-2015-03.pdf
 * Presentation: http://www.cs.technion.ac.il/~gilga/UCLA_and_TCL.pptx
 * 
 * @author  gilga1983@gmail.com (Gil Einziger) 
 *  
 */
public class TinySetIndexing {
	
	
	// for performance - for functions that need to know both the start and the end of the chain. 
	public static int ChainStart; 
	public static int ChainEnd; 
	
	public static int getChainStart(HashedItem fpaux,long[] chainIndex, long[] isLastIndex)
	{
		int requiredChainNumber = rank(chainIndex[fpaux.set], fpaux.chainId);
		int currentChainNumber =rank(isLastIndex[fpaux.set], requiredChainNumber);
		int currentOffset = requiredChainNumber;
		long tempisLastIndex = isLastIndex[fpaux.set]>>>requiredChainNumber;
		while(currentChainNumber<requiredChainNumber)
		{
			currentChainNumber +=tempisLastIndex&1l;
			currentOffset++;
			tempisLastIndex>>>=1;
		}
		return currentOffset; 
	}
	public static int rank(long index, int bitNum)
	{
		return Long.bitCount(index&(~(-1L<<bitNum)));
	}
	public static int getChainEnd(HashedItem fpaux,long[] chainIndex, long[] isLastIndex)
	{
		int requiredChainNumber = rank(chainIndex[fpaux.set], fpaux.chainId);
		int currentChainNumber = rank(isLastIndex[fpaux.set], requiredChainNumber);
		int currentOffset = requiredChainNumber;
		
		long tempisLastIndex = isLastIndex[fpaux.set]>>>requiredChainNumber;
		while(currentChainNumber<requiredChainNumber)
		{
			currentChainNumber +=tempisLastIndex&1l;
			currentOffset++;
			tempisLastIndex>>>=1;

		}
		while((tempisLastIndex&1l)==0)
		{
			currentOffset++;
			tempisLastIndex>>>=1;

		}
		return currentOffset; 
	}
	public static int getChain(HashedItem fpaux,long[] chainIndex, long[] isLastIndex)
	{
		int requiredChainNumber = rank(chainIndex[fpaux.set], fpaux.chainId);
		int currentChainNumber = rank(isLastIndex[fpaux.set], requiredChainNumber);
		int currentOffset = requiredChainNumber;
		
		long tempisLastIndex = isLastIndex[fpaux.set]>>>requiredChainNumber;
		while(currentChainNumber<requiredChainNumber)
		{
			currentChainNumber +=tempisLastIndex&1l;
			currentOffset++;
			tempisLastIndex>>>=1;

		}
		TinySetIndexing.ChainStart = currentOffset;
		while((tempisLastIndex&1l)==0)
		{
			currentOffset++;
			tempisLastIndex>>>=1;

		}
		TinySetIndexing.ChainEnd = currentOffset;
		return currentOffset; 
	}
	public static boolean chainExist(long chainIndex, int chainId)
	{
//		long mask = ;
		return (chainIndex|(1l<<chainId))==chainIndex;
	}
	public static int addItem(HashedItem fpaux, long[] chainIndex, long[] isLastIndex) {
		
		int offset  = getChainStart(fpaux,chainIndex,isLastIndex);
		long mask = 1l<<fpaux.chainId;
		isLastIndex[fpaux.set] = extendZero(isLastIndex[fpaux.set],offset);

		//if the item is new... 
		if((mask|chainIndex[fpaux.set]) != chainIndex[fpaux.set])
		{
			// add new chain to IO.
			chainIndex[fpaux.set]|=mask;
			// mark item as last in isLastIndex.
			isLastIndex[fpaux.set]|=(1l<<offset);
		}
		
		return offset;
	}

	private static long extendZero(final long isLastIndex, final int offset) {
		long constantPartMask = (1l<<offset)-1;
		return (isLastIndex&constantPartMask)|((isLastIndex<<1l)& (~(constantPartMask))&(~(1l<<offset)));
	
	}
	private static long shrinkOffset(long isLastIndex, int offset) {
		long conMask = ((1l<<offset) -1);
		return  (isLastIndex&conMask)|(((~conMask)&isLastIndex)>>>1);
	}
	public static void removeItem(HashedItem fpaux, long[] chainIndex,long[] isLastIndex)
	{
		int chainStart = getChainStart(fpaux,chainIndex,isLastIndex);
		// avoid an if command: either update chainIndex to the new state or keep it the way it is. 
		chainIndex[fpaux.set] = (isLastIndex[fpaux.set]&(1l<<chainStart))!=0l?chainIndex[fpaux.set]&~(1l<<fpaux.chainId):chainIndex[fpaux.set];
		// update isLastIndex.
		isLastIndex[fpaux.set] = shrinkOffset(isLastIndex[fpaux.set],chainStart);

	}

	




	



}
