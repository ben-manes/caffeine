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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.typesafe.config.Config;

/**
 * The simulator's configuration. A policy can extend this class as a convenient way to extract
 * its own settings.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class BasicSettings {
  public enum FileFormat {
    ADDRESS,
    CAFFEINE_TEXT,
    CAFFEINE_BINARY,
    LIRS,
  }

  private final Config config;

  public BasicSettings(Config config) {
    this.config = config;
  }

  public List<String> policies() {
    return config().getStringList("policies");
  }

  public AdmissionSource admission() {
    return new AdmissionSource();
  }

  public int maximumSize() {
    return config().getInt("maximum-size");
  }

  public boolean isFile() {
    return config().getString("source").equals("file");
  }

  public boolean isSynthetic() {
    return config().getString("source").equals("synthetic");
  }

  public FileSource fileSource() {
    checkState(isFile());
    return new FileSource();
  }

  public SyntheticSource synthetic() {
    checkState(isSynthetic());
    return new SyntheticSource();
  }

  /** Returns the config resolved at the simulator's path. */
  protected Config config() {
    return config;
  }

  public final class AdmissionSource {
    public List<String> admittors() {
      return config().getStringList("admission.admittors");
    }
    public double eps() {
      return config().getDouble("admission.eps");
    }
    public double confidence() {
      return config().getDouble("admission.confidence");
    }
  }

  public final class FileSource {
    public Path path() {
      return Paths.get(config().getString("file.path"));
    }
    public FileFormat format() {
      return FileFormat.valueOf(config().getString("file.format").toUpperCase());
    }
  }

  public final class SyntheticSource {
    public String distribution() {
      return config().getString("synthetic.distribution");
    }
    public int events() {
      return config().getInt("synthetic.events");
    }
    public Counter counter() {
      return new Counter();
    }
    public Exponential exponential() {
      return new Exponential();
    }
    public Hotspot hotspot() {
      return new Hotspot();
    }
    public Uniform uniform() {
      return new Uniform();
    }

    public final class Counter {
      public int start() {
        return config().getInt("synthetic.counter.start");
      }
    }
    public final class Exponential {
      public double mean() {
        return config().getDouble("synthetic.exponential.mean");
      }
    }
    public final class Hotspot {
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
    public final class Uniform {
      public int lowerBound() {
        return config().getInt("synthetic.uniform.lower-bound");
      }
      public int upperBound() {
        return config().getInt("synthetic.uniform.upper-bound");
      }
    }
  }
}
