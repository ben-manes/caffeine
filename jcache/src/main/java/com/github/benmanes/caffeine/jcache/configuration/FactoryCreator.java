/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.jcache.configuration;

import javax.cache.configuration.Factory;

/**
 * An object capable of providing factories that produce an instance for a given class name.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@FunctionalInterface
public interface FactoryCreator {

  /**
   * Returns a {@link Factory} that will produce instances of the specified class.
   *
   * @param className the fully qualified name of the desired class
   * @param <T> the type of the instances being produced
   * @return a {@link Factory} for the specified class
   */
  <T> Factory<T> factoryOf(String className);
}
