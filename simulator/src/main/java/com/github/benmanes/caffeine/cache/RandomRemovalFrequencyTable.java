package com.github.benmanes.caffeine.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Frequency;
import com.typesafe.config.Config;
/**
 * A probabilistic multiset for estimating the popularity of an element within a time window. The
 * maximum frequency of an element. The size of the sample in relation to the cache size can be controlled with sampleFactor. 
 * Instead of halfing the popularity of elements a random element is dropped when table is full. 
 *
 * This class is used to check the feasibility of using TinyTable instead of Count Min Sketch.  
 * @author gilg1983@gmail.com (Gil Einziger)
 */
public class RandomRemovalFrequencyTable<E> implements Frequency<E> {
  // sum of total items. 
  final int maxSum;
  //total sum of stored items. 
  int currSum =0;
  // controls both the max count and how many items are remembered (the sum). 
  final int sampleFactor =8;
  // used to dropped items at random. 
  Random r  = new Random(0);
  // a place holder for TinyTable. 
  HashMap<E,Integer> table;
  
  
  public RandomRemovalFrequencyTable(Config config) {
    BasicSettings settings = new BasicSettings(config);
    maxSum = sampleFactor*settings.maximumSize();
    table = new HashMap<E,Integer>(maxSum);
  }


  @Override
  public int frequency(E e) {
    return table.getOrDefault(e, 0);
  }

  @Override
  public void increment(E e) {
    // read and increments value. 
    int value = table.getOrDefault(e, 0) +1;
    // if the value is big enough there is no point in dropping a value so we just quit. 
   if(value>sampleFactor)
      return;
   // putting the new value. 
   table.put(e, value);
   // advancing the nr Items. 
   if(currSum<maxSum)
     currSum++;
   
    
    // once the table is full every item that arrive some other item leaves. 
    // This implementation is lacking as the probability to forget each item does not depend on frequency. (so items do not converge to their true frequency. 
    // but I do not think it is worth fixing right now as this is just a model. 
    if(currSum==maxSum)
    {
      ArrayList<E> array = new ArrayList<E>(table.keySet());
      E itemToRemove = array.get(r.nextInt(array.size()));
      value = table.remove(itemToRemove);
     
      if(value>1)
      {
        table.put(itemToRemove, value-1);
      
      }
   
    }

  }

}


