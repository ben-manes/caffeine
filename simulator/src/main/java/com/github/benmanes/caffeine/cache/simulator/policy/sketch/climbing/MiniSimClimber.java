/**
 * 
 */
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing;

import com.github.benmanes.caffeine.cache.simulator.policy.sketch.WindowTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.WindowTinyLfuPolicy.WindowTinyLfuSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimberWindowTinyLfuPolicy.HillClimberWindowTinyLfuSettings;
import com.sangupta.murmur.Murmur3;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * A MinSim version for W-TinyLFU
 * 
 * @author ohadey@gmail.com (Ohad Eytan)
 */
public final class MiniSimClimber implements HillClimber {

  private double prevPercent;
  private int sample;
  private long prevMisses[];
  private int cacheSize;
  private WindowTinyLfuPolicy[] minis;
  private int R;

  public MiniSimClimber(Config config) {
    HillClimberWindowTinyLfuSettings settings = new HillClimberWindowTinyLfuSettings(config);
    R = settings.maximumSize() / 1000 > 100 ? 1000 : settings.maximumSize() / 100;
    Config myConfig = ConfigFactory.parseString("maximum-size = " + settings.maximumSize() / R);
    myConfig = myConfig.withFallback(config);
    WindowTinyLfuSettings wsettings = new WindowTinyLfuSettings(myConfig);

    this.prevPercent = 1 - settings.percentMain().get(0);
    this.cacheSize = settings.maximumSize();
    this.minis = new WindowTinyLfuPolicy[101];
    for (int i = 0; i < minis.length; i++) {
      minis[i] = new WindowTinyLfuPolicy(1.0 - i / 100.0, wsettings);
    }
    prevMisses = new long[101];
  }

  @Override
  public void doAlways(long key) {
    sample++;
    String skey = String.valueOf(key);
    if (Murmur3.hash_x86_32(skey.getBytes(), skey.length(), 0x7f3a2142) % R < 1) {
      for (int i = 0; i < minis.length; i++) {
        minis[i].record(key);
      }
    }
  }

  @Override
  public void onHit(long key, QueueType queue) {
  }

  @Override
  public void onMiss(long key) {
  }

  @Override
  public Adaptation adapt(int windowSize, int protectedSize) {
    if (sample > 1000000) {
      long periodMisses[] = new long[101];
      for (int i = 0; i < minis.length; i++) {
        periodMisses[i] = minis[i].stats().missCount() - prevMisses[i];
        prevMisses[i] = minis[i].stats().missCount();
      }
      int minIndex = 0;
      for (int i = 1; i < periodMisses.length; i++) {
        if (periodMisses[i] < periodMisses[minIndex]) {
          minIndex = i;
        }
      }

      double oldPercent = prevPercent;
      double newPercent = prevPercent = minIndex < 80 ? minIndex / 100.0 : 0.8;

      sample = 0;
      if (newPercent > oldPercent) {
        return new Adaptation(Adaptation.Type.INCREASE_WINDOW, (int) ((newPercent - oldPercent) * cacheSize));
      }
      return new Adaptation(Adaptation.Type.DECREASE_WINDOW, (int) ((oldPercent - newPercent) * cacheSize));
    }
    return new Adaptation(Adaptation.Type.HOLD, 0);
  }
}
