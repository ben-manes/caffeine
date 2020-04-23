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

public class MyGenericPolicy extends MyLinkedPolicy {
  public CacheMemSystem cache_mem_system;
  public long cur_key;
  
  public MyGenericPolicy (Admission admission, EvictionPolicy policy, Config config) {
    super (admission, policy, config);
    cache_mem_system = new CacheMemSystem ();
  }

  @Override
  public void record(AccessEvent event) {
    cur_key = event.key();
    boolean is_in_cache = super.IsInCache(cur_key);
    cache_mem_system.HandleReq (cur_key, is_in_cache);
    super.record(event); 
  }

  @Override
  public void IndicateEviction (long key) {
    this.cache_mem_system.HandleCacheChange(key, Op.Remove);
  }

}
