package com.github.benmanes.caffeine.cache.simulator.cache_mem_system;


import java.util.Set;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.*; // BANG: cannot import this private class
import com.typesafe.config.Config;


enum Op {Add, Remove};


//A version of MyCachePolicy which implements the i/f Policy This is the i/f implemented by most the opt.UnboundedPolicy.
public final class CacheMemSystem {
  public Integer cache_size;
	public CBF<Long> stale_indicator, updated_indicator;
	public double accs_cnt, hit_cnt, fp_miss_cnt, tn_miss_cnt, fn_miss_cnt;
	private int staleness_fp_miss_cnt;  // The number of FP misses caused due to indicator's staleness. The current calculation gives strange results. 
	public long cur_key;
	public double designed_indicator_fpr; // The designed False Positive Ratio the indicator should have 
	final Integer max_num_of_requests = 700000;
	public Integer num_of_cache_changes_since_last_update;
	public double num_of_cache_changes_between_updates = 2;
	private double measured_fpr, measured_fnr; // False Postive, Negative Ratios obtained in practice
	private double hit_ratio, expected_service_cost, NI_expected_service_cost; //NI = No Indicator
  private Integer snd_update_cnt;
  public Config config;
  public Set<Policy> my_policies_set;
  public FrequentlyUsedPolicy my_policy; //(Admission admission, EvictionPolicy policy, Config config)
  
  // C'tor
  // config - the configuration file
  public CacheMemSystem (Config config) {  
    this.config = config; 
    ResetSystem ();
  }

  // Reset the cache-mem system. 
  // Call this method before each run of a trace
  private void ResetSystem () {
    cache_size = MyConfig.GetIntParameterFromConfFile("maximum-size");
    designed_indicator_fpr =  MyConfig.GetDoubleParameterFromConfFile("designed-indicator-fpr");
    accs_cnt 	 = 0;
    hit_cnt 	 = 0;
    tn_miss_cnt = 0;
    fn_miss_cnt = 0;
    fp_miss_cnt = 0;
    staleness_fp_miss_cnt = 0;
    updated_indicator = new CBF<Long>(cache_size, designed_indicator_fpr); // Create a new empty updated indicator
    snd_update_cnt = 0;
    SendUpdate (); // Copy the updated indicator to a stale indicator   
  }

  private void SendUpdate () {
    stale_indicator = new CBF<Long> (updated_indicator); // Copy the stale indicator to the updated indicator
    num_of_cache_changes_since_last_update = 0;
    snd_update_cnt++;
  }
  
  private void HandleCacheChange (Long key, Op op) {
    this.num_of_cache_changes_since_last_update++;
    updated_indicator.HandleCacheChange (key, op);           
    if (num_of_cache_changes_since_last_update >= num_of_cache_changes_between_updates) {
      SendUpdate();
      num_of_cache_changes_since_last_update = 0;
    }
  }

  // Checks whether a given key is in the cache, by calling to the relevant policy. 
  private boolean IsInCache (long key) {
    return false; //$$
//    return my_policy.IsInCache(key);
      //return com.github.benmanes.caffeine.cache.simulator.policy.linked.FrequentlyUsedPolicy.IsInCache (key);
  }


  /** Handle a user's request for an item. */
  public void record(AccessEvent event) {
    accs_cnt++;
    cur_key = event.key().longValue(); 
    boolean key_is_in_cache = IsInCache (cur_key); 
        
    //Query the stale indicator
    if (stale_indicator.Query(cur_key)) { 
       
      // Positive indication
      if (key_is_in_cache) { // True Positive 
        hit_cnt++;         
      }
      else { // False Positive
        fp_miss_cnt++; //A miss due to false-positive indication
        if (!updated_indicator.Query(cur_key)) {// stale indicator positively replies, updated indicator negatively reply  
          staleness_fp_miss_cnt++; // False Positive which happened due to staleness
        }
      }
//         AccessCache (this.cur_key, this.cur_val);
     }
     
     else { //Negative indication
//       if (key_is_in_cache) {
//             this.fn_miss_cnt++; //A miss due to false negative indication
//       }
//       else {
//         this.tn_miss_cnt++; //A miss due to true negative indication
//       }
//         this.InformCache(); // After the item "was fetched from the memory", inform the cache about the requested item
     }
     
//     // If the key has just been cached, need to inform the updated indicator  
//     if (!key_is_in_cache && IsInCache(this.cur_key)) {
//       HandleCacheChange (this.cur_key, Op.Add);       
//     }

  }
}


