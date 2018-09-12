package com.github.benmanes.caffeine.cache.simulator.admission.countmin4;

import com.github.benmanes.caffeine.cache.simulator.admission.Frequency;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.Indicator;
import com.typesafe.config.Config;

public class IndicatorResetCountMin4 implements Frequency {
  private final ClimberResetCountMin4 sketch;

  int hintSum;
  int hintCount;
  int prevHintSum;
  int prevHintCount;
  Indicator indicator;

  public IndicatorResetCountMin4(Config config) {
    super();
    this.sketch = new ClimberResetCountMin4(config);
    this.indicator = new Indicator(config);
  }

  @Override
  public int frequency(long e) {
    return sketch.frequency(e);
  }

  @Override
  public void increment(long e) {
    sketch.increment(e);
    indicator.record(e);
  }

  @Override
  public void reportMiss() {
    if (sketch.getEventsToCount() <= 0) {
      sketch.resetEventsToCount();
      double ind = getIndicator();
      sketch.setStep(hintToStep(ind));
      indicator.reset();
    }
  }

  private double getIndicator() {
    return indicator.getIndicator();
  }

  private int hintToStep(double ind) {
    return (int) (ind * 30);
  }

  public int getEventsToCount() {
    return sketch.getEventsToCount();
  }

  public int getPeriod() {
    return sketch.getPeriod();
  }

  public int getHintSum() {
    return prevHintSum;
  }

  public int getHintCount() {
    return prevHintCount;
  }

}
