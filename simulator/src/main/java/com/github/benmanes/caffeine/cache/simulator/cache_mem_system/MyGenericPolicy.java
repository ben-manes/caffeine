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


// A slightly-more generic envelope, which can be used (possibly with some little changes) by several "MyXXXPolicy"
// MyGenericPolicy adds to "MyXXXPolicy" a CacheMemSystem, which does the following:
// - tracks changes in the cache, 
// - collects stats (e.g., counts of true / false positives and true / false negatives)
// - updates an indicator
// - uses the indicator's indications as an "access strategy" to the cache / directly to the "mem".
public class MyGenericPolicy extends MyLinkedPolicy {
  public CacheMemSystem cache_mem_system;
  public long cur_key;
   
  public MyGenericPolicy (Admission admission, EvictionPolicy policy, Config config) {
    super (admission, policy, config);
    cache_mem_system = new CacheMemSystem ();
  }

  // Intercept requests for keys and insertions to the cache
  @Override
  public void record(AccessEvent event) {
    cur_key = event.key();
    boolean is_in_cache = super.IsInCache(cur_key);
    cache_mem_system.HandleReq (cur_key, is_in_cache);
    super.record(event); 
  }

  // Intercept evictions from the cache
  @Override
  public void IndicateEviction (long key) {
    this.cache_mem_system.HandleCacheChange(key, Op.Remove);
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
