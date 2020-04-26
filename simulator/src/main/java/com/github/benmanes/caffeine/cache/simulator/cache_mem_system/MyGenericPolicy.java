package com.github.benmanes.caffeine.cache.simulator.cache_mem_system;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.LinkedPolicy;
import com.typesafe.config.Config;


enum Op {Add, Remove};

// A slightly-more generic envelope, which can be used (possibly with some little changes) by several "MyXXXPolicy"
// MyGenericPolicy adds to "MyXXXPolicy" the following capabilities:
// - Tracking changes in the cache, 
// - Collecting system-levle stats (e.g., counts of true / false positives and true / false negatives)
// - Updating indicators.
// - Using the indicator's indications as an "access strategy" to the cache / directly to the "mem".
public class MyGenericPolicy extends LinkedPolicy {
  public long cur_key;
  public int cache_size;
  public CBF<Long> stale_indicator, updated_indicator;
  public double designed_indicator_fpr; // The designed False Positive Ratio the indicator should have 
  public Integer num_of_cache_changes_since_last_update;
  public int num_of_cache_changes_between_updates;
  private Integer snd_update_cnt;
  private static boolean finish_flag; // Reset upon init; set when first policy finishes sim 

  // debug $$$$$$$$$
  private int insert_cnt, rmv_cnt;

  // C'tor
  public MyGenericPolicy (Admission admission, EvictionPolicy policy, Config config) {
    super (admission, policy, config);
    BasicSettings settings = new BasicSettings(config);
    cache_size = settings.maximumSize(); 
    num_of_cache_changes_between_updates = 1; //(int) (cache_size * 0.2);
    designed_indicator_fpr = MyConfig.GetDoubleParameterFromConfFile("designed-indicator-fpr");
    updated_indicator = new CBF<Long>(cache_size, designed_indicator_fpr); // Create a new empty updated indicator
    snd_update_cnt = 0;
    finish_flag = false;
    SendUpdate ();
    
  }

  // Intercept requests for keys and insertions to the cache
  @Override
  public void record(AccessEvent event) {
    cur_key = event.key();
    boolean is_in_cache = super.IsInCache(cur_key);
    boolean stale_indication = stale_indicator.Query (cur_key);
    
    if (stale_indication) { // Positive indication
      
      if (is_in_cache) { // True positive indication - nothing special to do
        super.record(event); 
        return;
      }
      else {// False Positive indication
        this.policyStats.recordFp();
        if (!updated_indicator.Query (cur_key)) { // stale indicator positively replies, while updated indicator negatively reply
          this.policyStats.recordStalenessFp();
        }
      }
    }
    else { //Negative indication
      if (is_in_cache) {
        this.policyStats.recordFn(); // False Negative
      }
      else {
        this.policyStats.recordTn(); // True Negative
      }
    }
    
    // A miss (either FP, TN or FN) --> the missed item is inserted into the cache.
    // Later, the cache policy may either evict an old item from the cache; or evict this newly-inserted item,  
    // which is equivalent to not admitting the new item into the cache.
    HandleCacheChange (cur_key, Op.Add);
    super.record(event); 
  }

  private void SendUpdate () {
    stale_indicator = new CBF<Long> (updated_indicator); // Copy the stale indicator to the updated indicator
    num_of_cache_changes_since_last_update = 0;
    snd_update_cnt++;
  }

  public void HandleCacheChange (long key, Op op) {
    num_of_cache_changes_since_last_update++;
    updated_indicator.HandleCacheChange (key, op);
    if (op == Op.Add)
      insert_cnt++;
    else
      rmv_cnt++;
    if (num_of_cache_changes_since_last_update >= num_of_cache_changes_between_updates) {
      SendUpdate();
      num_of_cache_changes_since_last_update = 0;
    }
  }

  // Intercept evictions from the cache
  @Override
  public void IndicateEviction (long key) {
    HandleCacheChange (key, Op.Remove);
  }

  @Override
  public void finished () {
    if (finish_flag) { // at least one other have already finished running
      return;
    }
    finish_flag = true;  
    System.out.printf ("Cache size = %d inter-update-ops = %d designed fpr = %.2f \n", 
        cache_size, this.num_of_cache_changes_between_updates, designed_indicator_fpr);
  }
  
}
