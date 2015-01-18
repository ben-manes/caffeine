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

import akka.actor.UntypedActor;

import com.typesafe.config.Config;

/**
 * The simulator's configuration. A policy can extend this class as a convenient way to extract
 * its own settings.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class BasicSettings {
  private final Config config;

  public BasicSettings(UntypedActor actor) {
    config = actor.getContext().system().settings().config();
  }

  public List<String> policies() {
    return config().getStringList("simulator.policies");
  }

  public int maximumSize() {
    return config().getInt("simulator.maximumSize");
  }

  public boolean isFile() {
    return config().getString("simulator.source").equals("file");
  }

  public FileSource fileSource() {
    checkState(isFile());
    return new FileSource();
  }

  public SyntheticSource synthetic() {
    checkState(!isFile());
    return new SyntheticSource();
  }

  protected Config config() {
    return config;
  }

  final class FileSource {
    public Path path() {
      return Paths.get(config().getString("simulator.file.path"));
    }
    public String format() {
      return config().getString("simulator.file.format");
    }
  }

  final class SyntheticSource {
    public String distribution() {
      return config().getString("simulator.synthetic.distribution");
    }
    public int items() {
      return config().getInt("simulator.synthetic.items");
    }
  }
}
