package com.github.benmanes.caffeine.cache.simulator.cache_mem_system;

import org.checkerframework.checker.nullness.qual.NonNull;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.CacheWriter;
import com.github.benmanes.caffeine.cache.Caffeine;
import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.opt.UnboundedPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.*; // BANG: cannot import this private class
import com.github.benmanes.caffeine.cache.simulator.policy.linked.FrequentlyUsedPolicy.EvictionPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

enum Op {Add, Remove};


//A version of MyCachePolicy which implements the i/f Policy This is the i/f implemented by most the opt.UnboundedPolicy.
public final class CacheMemSystem implements Policy {
  private final PolicyStats policyStats;
//  final Long2ObjectMap<Node> cache;
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
    policyStats = new PolicyStats("CacheMemSystem");
    this.config = config; 
    ResetSystem ();
    GenMyPolicy ();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  // This method is called by Registry.java, upon reading application.conf file and initializing the simulation.
  // config holds the parameters in the configuration file (e.g., application.conf).
  // The policies are returned as a set, because sometimes a single policy has multiple sub-policies.
  public static Set<Policy> policies(Config config) {
    return ImmutableSet.of(new CacheMemSystem(config));
  }

  @Override
  public Set<Characteristic> characteristics() {
    return Sets.immutableEnumSet(WEIGHTED);
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  private void GenMyPolicy () {

    BasicSettings settings = new BasicSettings(config);
    EvictionPolicy eviction_policy = EvictionPolicy.MFU; 
    my_policies_set = settings.admission().stream().map(admission ->
    new FrequentlyUsedPolicy(admission, eviction_policy, config)).collect(toSet());
    // Now my_policies_set holds a set of policy with a single item: the FrequentlyUsedPolicy. 
    
//    System.out.println ("The num of elements in the set is" + my_policies_set.size());
//    System.exit (0);
    
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


  @Override
  /** Handle a user's request for an item. */
  public void record(AccessEvent event) {
    accs_cnt++;
    policyStats.recordOperation();
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



//$$ A version of MyCachePolicy which implements the i/f KeyOnlyPolicy (defined within i/f Policy, within policy.java). This is the i/f 
//implemented by most non-opt policies.
//public class MyCachePolicy implements KeyOnlyPolicy {
//	  PolicyStats policyStats;
//
//	
//	  @Override
//	  public void record(long key) {
//	  }
//
//	  @Override
//	  public PolicyStats stats() {
//	    return policyStats;
//	  }
//	
//}

/*
public class CacheMemSystem<K> {
	
	public Cache<K, Value> cache;
    public Integer cache_size;
    public CBF<K> stale_indicator, updated_indicator;
    public double accs_cnt, hit_cnt, fp_miss_cnt, tn_miss_cnt, fn_miss_cnt;
	private int staleness_fp_miss_cnt;  // The number of FP misses caused due to indicator's staleness. The current calculation gives strange results. 
    public K cur_key;
    public Value cur_val;
    public double designed_indicator_fpr; // The designed False Positive Ratio the indicator should have 
    final Integer max_num_of_requests = 700000;
    public Scanner scanner; // A scanner is a kind of Java's file descriptor for reading from the trace file
    public Integer num_of_cache_changes_since_last_update;
    public double num_of_cache_changes_between_updates = 2;
	private double measured_fpr, measured_fnr; // False Postive, Negative Ratios obtained in practice
	private double hit_ratio, expected_service_cost, NI_expected_service_cost; //NI = No Indicator

	//Debug
	private Integer snd_update_cnt;
	
    //C'tor
    CacheMemSystem () {
    	this.cur_val = new Value ("V"); // Currently using the same default Value for all items
    }
    
    
    public boolean IsInCache (K key) {
    	Value rd_val = this.cache.getIfPresent(key);
        return (rd_val != null);
    }

    public void PrintIsInCache (K key){
        if (IsInCache(key))
            System.out.println("key " + key + " is in the cache");
        else
            System.out.println("key " + key + " NOT cached");
    }

    // Look for a requested key in the cache
    // The cache MAY decide to store the item. 
    public void AccessCache (K key, Value val) {
        this.cache.put(key, val);
        this.cache.cleanUp(); //$$$ Wait for eviction, if required. See https://www.baeldung.com/java-caching-caffeine
    }

    // Inform the cache about an a requested key (which was obtained by directly accessing the memory)
    // The cache MAY decide to store the item. 
    public void InformCache () {
        this.cache.get(this.cur_key, k -> Value.GenValueFromString(this.cur_val.getData()));
        this.cache.cleanUp(); //$$$ Wait for eviction, if required. See https://www.baeldung.com/java-caching-caffeine
    }

    // Handle a user request for this.cur_key
    public void HandleReq () {
        this.accs_cnt++;
        boolean key_is_in_cache = IsInCache(this.cur_key);
        if (this.stale_indicator.Query(this.cur_key)) { //Query the stale indicator
        	
        	//Positive indication
        	if (key_is_in_cache) {
                this.hit_cnt++;      		
        	}
        	else {
        		this.fp_miss_cnt++; //A miss due to false-positive indication
        		if (!this.updated_indicator.Query(this.cur_key)) { // stale indicator positively replies, while updated indicator negatively reply  
        			this.staleness_fp_miss_cnt++;
        		}
        	}
            AccessCache (this.cur_key, this.cur_val);
        }
        
        else { //Negative indication
        	if (key_is_in_cache) {
                this.fn_miss_cnt++; //A miss due to false negative indication
        	}
        	else {
        		this.tn_miss_cnt++; //A miss due to true negative indication
        	}
            this.InformCache(); // After the item "was fetched from the memory", inform the cache about the requested item
        }
        
        // If the key has just been cached, need to inform the updated indicator  
        if (!key_is_in_cache && IsInCache(this.cur_key)) {
        	HandleCacheChange (this.cur_key, Op.Add);      	
        }
    }

    public void HandleCacheChange (K key, Op op) {
    	this.num_of_cache_changes_since_last_update++;
    	this.updated_indicator.HandleCacheChange (key, op);      	   	
    	if (this.num_of_cache_changes_since_last_update >= this.num_of_cache_changes_between_updates) {
    		SendUpdate();
    		this.num_of_cache_changes_since_last_update = 0;
    	}
    }
    
    private void SendUpdate () {
    	this.stale_indicator = new CBF<K> (this.updated_indicator); // Copy the stale indicator to the updated indicator
		this.num_of_cache_changes_since_last_update = 0;
		this.snd_update_cnt++;
    }

    // Reset the cache-mem system. 
    // Call this method before each run of a trace
    public void ResetSystem () {

    	// Move scanner to the beginning of the trace file
        this.scanner = MyConfig.GetTraceScanner();

        this.accs_cnt 	 = 0;
        this.hit_cnt 	 = 0;
        this.tn_miss_cnt = 0;
        this.fn_miss_cnt = 0;
        this.fp_miss_cnt = 0;
        this.staleness_fp_miss_cnt = 0;
        this.updated_indicator = new CBF<K>(this.cache_size, this.designed_indicator_fpr); // Create a new empty updated indicator
    	snd_update_cnt = 0;
        SendUpdate (); // Copy the updated indicator to a stale indicator
        
        // Generate a cache
	    this.cache = Caffeine.newBuilder()
                .maximumSize (this.cache_size)
                
                // Caffeine allows coding here a handler, which should be activated upon each write to the cache.
                // However, this didn't work well when using different kinds of cache accesses (get, put, ...). 
                // Therefore the write method is currently a dummy empty method. 
                // A write to the cache is identified by checking whether a requested key was added to the cache upon 
                // each request.
                .writer(new CacheWriter<K, Value>() {
                    	public void write(K key, Value val) { 
                    }
//					@Override
                    // Caffeine allows coding here a handler, which should be activated upon each deletion of an item from the cache.
					public void delete(K key, Value val,
						com.github.benmanes.caffeine.cache.@NonNull RemovalCause cause) {
                    	HandleCacheChange (key, Op.Remove);
					}
                  })                
                .build();
	    
	    //Debug
    }

//   private void CalcServiceCost () {
//	   measured_fnr = fn_miss_cnt / accs_cnt;
//   	   measured_fpr = fp_miss_cnt / (accs_cnt - hit_cnt);
//	   hit_ratio = hit_cnt / accs_cnt;
//	   NI_expected_service_cost = 1 + (1 - hit_ratio)*missp; //No Indicator: always accs $ (costs 1) + miss cost
//	   expected_service_cost = hit_ratio + measured_fpr + (1 - hit_ratio)*missp;   
//   } 
   
    
    public void PrintStats () {
//    	CalcServiceCost ();
  	   measured_fnr = fn_miss_cnt / accs_cnt;
   	   measured_fpr = fp_miss_cnt / (accs_cnt - hit_cnt);
	   hit_ratio = hit_cnt / accs_cnt;
	   
       System.out.println ("hit ratio = " + hit_ratio + " fpr = " + measured_fpr + " fnr = " + measured_fnr);
       double missp = 3; // Miss Penalty
       NI_expected_service_cost = 1 + (1 - hit_ratio)*missp; //No Indicator: always accs $ (costs 1) + miss cost
	   expected_service_cost = hit_ratio + measured_fpr + (1 - hit_ratio)*missp;   
//       double PI_service_cost = hit_ratio + (1 - hit_ratio)*missp;
       System.out.println ("M = " + missp + " NI S.cost = " + NI_expected_service_cost + 
    						" ECM S. cost = " + expected_service_cost);

//       missp = 100; // Miss Penalty
//       NI_expected_service_cost = 1 + (1 - hit_ratio)*missp; //No Indicator: always accs $ (costs 1) + miss cost
// 	   expected_service_cost = hit_ratio + measured_fpr + (1 - hit_ratio)*missp;   
//       System.out.println ("M = " + missp + " NI S.cost = " + NI_expected_service_cost + 
//				" ECM S. cost = " + expected_service_cost);
    
    } 
    

    public void simulate_trace () {
        while (this.scanner.hasNextInt()) {
    	      if (this.accs_cnt >= this.max_num_of_requests) // For short debug traces
    	    	  break;
      	      this.cur_key = (K) Integer.valueOf(scanner.nextInt()); // Read the next key from the trace 		
      	      HandleReq ();
      	}
      	this.PrintStats ();    	
    }
    
    public void simulate () {
       
    	// Read parameters from the file CacheTry.conf
        this.cache_size = MyConfig.GetIntParameterFromConfFile("cache-size");
        double initial_inter_arrival_interval = 1;
        double final_inter_arrival_interval = 2.0 * cache_size;
        int num_of_inter_arrival_interval_steps = 2;
        double initial_indicator_fpr = 0.01;
        double final_indicator_fpr = 0.05;
        int num_of_indicator_fpr_steps = 2;
        num_of_cache_changes_between_updates = initial_inter_arrival_interval;
    	for (int updater_cnt = 0; updater_cnt < num_of_inter_arrival_interval_steps; updater_cnt++) {
    		designed_indicator_fpr = initial_indicator_fpr;
    		for (int fpr_cnt = 0; fpr_cnt < num_of_indicator_fpr_steps; fpr_cnt++) {
            	System.out.println ("designed indicator fpr = " + designed_indicator_fpr);
	        	ResetSystem (); 
	            simulate_trace ();
//	    		System.out.println ("Num of sent updates = " + snd_update_cnt + "\n");
	            System.out.println ("");
	    		designed_indicator_fpr += (final_indicator_fpr - initial_indicator_fpr) / (num_of_indicator_fpr_steps-1); 
        	}
    		num_of_cache_changes_between_updates += (final_inter_arrival_interval - initial_inter_arrival_interval) / (num_of_inter_arrival_interval_steps-1);
        }
    }

    public static void main () {
	}
} 
*/