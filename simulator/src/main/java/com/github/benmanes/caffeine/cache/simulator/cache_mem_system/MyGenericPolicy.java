package com.github.benmanes.caffeine.cache.simulator.cache_mem_system;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import com.github.benmanes.caffeine.cache.simulator.cache_mem_system.*;
import static java.util.Locale.US;
import static java.util.stream.Collectors.toSet;

import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.MyLinkedPolicy;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;


enum Op {Add, Remove};

// A slightly-more generic envelope, which can be used (possibly with some little changes) by several "MyXXXPolicy"
// MyGenericPolicy adds to "MyXXXPolicy" the following capabilities:
// - Tracking changes in the cache, 
// - Collecting system-levle stats (e.g., counts of true / false positives and true / false negatives)
// - Updating indicators.
// - Using the indicator's indications as an "access strategy" to the cache / directly to the "mem".
public class MyGenericPolicy extends MyLinkedPolicy {
  public long cur_key;
  public Integer cache_size;
  public CBF<Long> stale_indicator, updated_indicator;
  public double designed_indicator_fpr; // The designed False Positive Ratio the indicator should have 
  public Integer num_of_cache_changes_since_last_update;
  public double num_of_cache_changes_between_updates = 2;
  private Integer snd_update_cnt;
   
  public MyGenericPolicy (Admission admission, EvictionPolicy policy, Config config) {
    super (admission, policy, config);
    cache_size = MyConfig.GetIntParameterFromConfFile("maximum-size");
    designed_indicator_fpr =  MyConfig.GetDoubleParameterFromConfFile("designed-indicator-fpr");
    updated_indicator = new CBF<Long>(cache_size, designed_indicator_fpr); // Create a new empty updated indicator
  }

  // Intercept requests for keys and insertions to the cache
  @Override
  public void record(AccessEvent event) {
    cur_key = event.key();
    boolean is_in_cache = super.IsInCache(cur_key);
    boolean stale_indication = stale_indicator.Query (cur_key);
    
   if (stale_indication) { // Check indication
      
      // False Positive indication
      if (!is_in_cache) {
        this.policyStats.recordFp();
        if (!updated_indicator.Query (cur_key)) { // stale indicator positively replies, while updated indicator negatively reply  
//          staleness_fp_miss_cnt++;
        }
      }  
   }
   
   else { //Negative indication
      if (is_in_cache) {
//            fn_miss_cnt++; //A miss due to false negative indication
      }
      else {
//        tn_miss_cnt++; //A miss due to true negative indication
      }
    }
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
    if (num_of_cache_changes_since_last_update >= num_of_cache_changes_between_updates) {
      SendUpdate();
      num_of_cache_changes_since_last_update = 0;
    }
  }

  // Intercept evictions from the cache
  @Override
  public void IndicateEviction (long key) {
    HandleCacheChange(key, Op.Remove);
  }

  /** Indicates that the recording has completed. */
  @Override
  public void finished() {
    System.out.println ("Yalla Hapoel*************\n ************\n **********\n");
    System.exit (0);
  }

  @Override
  public PolicyStats stats() {
    System.out.println ("Yalla Hapoel*************\n ************\n **********\n");
    System.exit (0);
    return super.stats();
  }

}
