/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache.simulator;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

import java.util.List;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.membership.FilterType;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceFormat;
import com.github.benmanes.caffeine.cache.simulator.report.ReportFormat;
import com.typesafe.config.Config;

/**
 * The simulator's configuration. A policy can extend this class as a convenient way to extract
 * its own settings.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class BasicSettings {
  private final Config config;

  public BasicSettings(Config config) {
    this.config = requireNonNull(config);
  }

  public ReportSettings report() {
    return new ReportSettings();
  }

  public int randomSeed() {
    return config().getInt("random-seed");
  }

  public int batchSize() {
    return config().getInt("batch-size");
  }

  public Set<String> policies() {
    return config().getStringList("policies").stream()
        .map(String::toLowerCase)
        .collect(toSet());
  }

  public Set<Admission> admission() {
    return config().getStringList("admission").stream()
        .map(String::toUpperCase)
        .map(Admission::valueOf)
        .collect(toSet());
  }

  public FilterType membershipFilter() {
    return FilterType.valueOf(config.getString("membership-filter").toUpperCase(US));
  }

  public TinyLfuSettings tinyLfu() {
    return new TinyLfuSettings();
  }

  public int maximumSize() {
    return config().getInt("maximum-size");
  }

  public boolean isFiles() {
    return config().getString("source").equals("files");
  }

  public boolean isSynthetic() {
    return config().getString("source").equals("synthetic");
  }

  public TraceFilesSettings traceFiles() {
    checkState(isFiles());
    return new TraceFilesSettings();
  }

  public SyntheticSettings synthetic() {
    checkState(isSynthetic());
    return new SyntheticSettings();
  }

  /** Returns the config resolved at the simulator's path. */
  public Config config() {
    return config;
  }

  public final class ReportSettings {
    public ReportFormat format() {
      return ReportFormat.valueOf(config().getString("report.format").toUpperCase(US));
    }
    public String sortBy() {
      return config().getString("report.sort-by").trim();
    }
    public boolean ascending() {
      return config().getBoolean("report.ascending");
    }
    public String output() {
      return config().getString("report.output").trim();
    }
  }

  public final class TinyLfuSettings {
    public String sketch() {
      return config().getString("tiny-lfu.sketch");
    }
    public boolean conservative() {
      return config().getBoolean("tiny-lfu.count-min.conservative");
    }
    public CountMin4Settings countMin4() {
      return new CountMin4Settings();
    }
    public CountMin64Settings countMin64() {
      return new CountMin64Settings();
    }

    public final class CountMin4Settings {
      public String reset() {
        return config().getString("tiny-lfu.count-min-4.reset");
      }
      public double countersMultiplier() {
        return config().getDouble("tiny-lfu.count-min-4.counters-multiplier");
      }
      public IncrementalSettings incremental() {
        return new IncrementalSettings();
      }
      public PeriodicSettings periodic() {
        return new PeriodicSettings();
      }
      public final class IncrementalSettings {
        public int interval() {
          return config().getInt("tiny-lfu.count-min-4.incremental.interval");
        }
      }
      public final class PeriodicSettings {
        public DoorkeeperSettings doorkeeper() {
          return new DoorkeeperSettings();
        }
      }
    }
    public final class CountMin64Settings {
      public double eps() {
        return config().getDouble("tiny-lfu.count-min-64.eps");
      }
      public double confidence() {
        return config().getDouble("tiny-lfu.count-min-64.confidence");
      }
    }
    public final class DoorkeeperSettings {
      private static final String basePath = "tiny-lfu.count-min-4.periodic";

      public boolean enabled() {
        return config().getBoolean(basePath + ".doorkeeper.enabled");
      }
      public double fpp() {
        return config().getDouble(basePath + ".doorkeeper.fpp");
      }
      public double expectedInsertionsMultiplier() {
        return config().getDouble(basePath + ".doorkeeper.expected-insertions-multiplier");
      }
    }
  }

  public final class TraceFilesSettings {
    public List<String> paths() {
      return config().getStringList("files.paths");
    }
    public TraceFormat format() {
      String format = config().getString("files.format").replace('-', '_').toUpperCase(US);
      return TraceFormat.valueOf(format);
    }
  }

  public final class SyntheticSettings {
    public String distribution() {
      return config().getString("synthetic.distribution");
    }
    public int events() {
      return config().getInt("synthetic.events");
    }
    public CounterSettings counter() {
      return new CounterSettings();
    }
    public UniformSettings uniform() {
      return new UniformSettings();
    }
    public ExponentialSettings exponential() {
      return new ExponentialSettings();
    }
    public HotspotSettings hotspot() {
      return new HotspotSettings();
    }
    public ZipfianSettings zipfian() {
      return new ZipfianSettings();
    }

    public final class CounterSettings {
      public int start() {
        return config().getInt("synthetic.counter.start");
      }
    }
    public final class UniformSettings {
      public int lowerBound() {
        return config().getInt("synthetic.uniform.lower-bound");
      }
      public int upperBound() {
        return config().getInt("synthetic.uniform.upper-bound");
      }
    }
    public final class ExponentialSettings {
      public double mean() {
        return config().getDouble("synthetic.exponential.mean");
      }
    }
    public final class HotspotSettings {
      public int lowerBound() {
        return config().getInt("synthetic.hotspot.lower-bound");
      }
      public int upperBound() {
        return config().getInt("synthetic.hotspot.upper-bound");
      }
      public double hotsetFraction() {
        return config().getDouble("synthetic.hotspot.hotset-fraction");
      }
      public double hotOpnFraction() {
        return config().getDouble("synthetic.hotspot.hot-opn-fraction");
      }
    }
    public final class ZipfianSettings {
      public int items() {
        return config().getInt("synthetic.zipfian.items");
      }
    }
  }
}
