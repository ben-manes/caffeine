package com.github.benmanes.caffeine.cache.simulator.cache_mem_system;

import orestes.bloomfilter.*;

//Counting Bloom Filter
public class CBF<K> implements Indicator<K> {
  public CountingBloomFilter<K> my_indicator; 
  public int cache_size; //Cache size is used for estimating the # of items to be concurrently stored in the CBF
	public double fpr; //False Positive Ratio
	public final int countingBits = 8;
  public int insert_cnt, rmv_cnt; 
	
  // C'tor
  CBF (Integer cache_size, double fpr) {
  	this.fpr = fpr;
//      this.cache_size = MyConfig.GetIntParameterFromConfFile("cache-size");      
      this.my_indicator = new FilterBuilder(this.cache_size, this.fpr)
      						//.size(10000) //bits to use
              				.countingBits(this.countingBits)
              				.buildCountingBloomFilter();
//      this.my_indicator = new FilterBuilder(10, 2);
      this.insert_cnt = 0;
      this.rmv_cnt = 0;
      //To get the # of hashes: this.my_indicator.getHashes
  }

  // Copy C'tore
  public CBF (CBF<K> cbf) {
  	this.fpr = cbf.fpr;
      this.cache_size = cbf.cache_size;      
      this.insert_cnt = 0;
      this.rmv_cnt = 0;
      this.my_indicator = cbf.my_indicator.clone();
      this.insert_cnt = 0;       
  }

  public boolean Query (K key)  {
  	return this.my_indicator.contains (key);
  }

  public void Insert (K key) {
  	this.my_indicator.add (key);
  	this.insert_cnt++;
  }

  public void Remove (K key) {
  	this.my_indicator.remove (key);
  	this.rmv_cnt++;
  }  

  public void SndUpdate () {
//  	my_indicator.clone();
  }
  
//  public void HandleCacheChangeWhileStale (K key, Op op) {
//  } 
//  
//  public void HandleCacheChange (K key, Op op) {
//		if (op == Op.Add)
//			Insert (key);
//		else //key was removed
//			Remove (key);
//  } 
	
}
