package com.github.benmanes.caffeine.cache.simulator.cache_mem_system;

public interface Indicator<K> {

    public boolean Query (K key);

    public void Insert (K key);

    public void Remove (K key);
  
    public void SndUpdate ();    
    
//    public void HandleCacheChangeWhileStale (K key, Op op);
//    
//    public void HandleCacheChange (K key, Op op);
       
}
