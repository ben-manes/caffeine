/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package mpsc;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import com.github.benmanes.caffeine.SingleConsumerQueue;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Group)
public class ConcurrentLinkedQueueBenchmark extends AbstractSingleConsumerBenchmark {
  Queue<Boolean> queue;

  @Setup
  public void setUp() {
    queue = new ConcurrentLinkedQueue<>();
  }

  @Override
  protected Queue<Boolean> queue() {
    return queue;
  }
}
