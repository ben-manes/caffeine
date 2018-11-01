package com.github.benmanes.caffeine.cache.simulator.policy.sketch;

import com.clearspring.analytics.stream.StreamSummary;
import java.util.Arrays;
import java.util.stream.IntStream;

import org.apache.commons.math3.stat.regression.SimpleRegression;

import com.github.benmanes.caffeine.cache.simulator.admission.countmin4.PeriodicResetCountMin4;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/*
 * An indicator for the recency vs. frequency bias.
 * 
 * @author ohadey@gmail.com (Ohad Eytan)
 */

public class Indicator {
  private final Hinter hinter;
  private final EstSkew estSkew;
  private final PeriodicResetCountMin4 sketch;
  private long sample;
  private int k;

  public Indicator(Config config) {
    super();
    Config myConfig = ConfigFactory.parseString("maximum-size = 5000");
    myConfig = myConfig.withFallback(config);
    this.hinter = new Hinter();
    this.estSkew = new EstSkew();
    this.sketch = new PeriodicResetCountMin4(myConfig);
    this.sample = 0;
    this.k = 70;
  }

  public void record(long key) {
    int hint = sketch.frequency(key);
    hinter.increment(hint);
    sketch.increment(key);
    estSkew.record(key);
    sample++;
  }

  public void reset() {
    hinter.reset();
    estSkew.reset();
    sample = 0;
  }

  public long getSample() {
    return sample;
  }

  public double getSkew() {
    return estSkew.estSkew(k);
  }

  public double getHint() {
    return hinter.getAverage();
  }

  public double getIndicator() {
    double skew = getSkew();
    return (getHint()) * (skew < 1 ? 1 - Math.pow(skew, 3) : 0) / 15.0;
  }

  private static class Hinter {
    int sum;
    int count;
    int[] freq = new int[16];

    public Hinter() {
    }

    public void increment(int i) {
      sum += i;
      count++;
      freq[i]++;
    }

    public void reset() {
      sum = count = 0;
      Arrays.fill(freq, 0);
    }

    public double getAverage() {
      return ((double) sum) / ((double) count);
    }

    public int getSum() {
      return sum;
    }

    public int getCount() {
      return count;
    }

    public int[] getFreq() {
      return freq;
    }
  }

  private static class EstSkew {
    StreamSummary<Long> stream;

    public EstSkew() {
      this.stream = new StreamSummary<>(1000);
    }

    public void record(long key) {
      stream.offer(key);
    }

    public void reset() {
      this.stream = new StreamSummary<>(1000);
    }

    public IntStream getTopK(int k) {
      return stream.topK(k).stream().mapToInt(counter -> (int) counter.getCount());
    }

    public double estSkew(int k) {
      SimpleRegression regression = new SimpleRegression();
      int[] idx = { 1 };
      getTopK(k).forEachOrdered(freq -> regression.addData(Math.log(idx[0]++), Math.log(freq)));
      return -regression.getSlope();
    }

  }

  public int[] getFreqs() {
    return hinter.getFreq();
  }

}
