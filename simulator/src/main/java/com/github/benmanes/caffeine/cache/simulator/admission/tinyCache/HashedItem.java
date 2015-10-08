package com.github.benmanes.caffeine.cache.simulator.admission.tinyCache;


/**
 * This is a wrapper class that represents a parsed hashed item. 
 * It contains set - a subtable for the item. 
 * 			   chain - a logical index within the set. 
 * 			   fingerprint - a value to be stored in the set and chain. 
 * 			   This implementation assues fingerprints of 1 byte. 
 * . 
 * @author gilga1983@gmail.com (Gil Einziger) 
 *
 */
public class HashedItem 
{
	public  int set;
	public  byte chainId;
	public  byte fingerprint;
	public long value; 
	public HashedItem(int set, byte chainid, byte fingerprit,long value)
	{
		this.set = set;
		this.chainId = chainid;
		this.fingerprint = fingerprit;
		this.value = value; 
		
	}

	public String toString()
	{
		return ("BucketID: " + set + " chainID:"+ chainId + " fingerprint: "+ fingerprint);
	}
	
	
	
	

}
