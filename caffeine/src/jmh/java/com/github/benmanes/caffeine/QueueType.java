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
package com.github.benmanes.caffeine;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.SynchronousQueue;

import com.google.common.collect.Queues;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public enum QueueType {
  SingleConsumerQueue() {
    @Override public <E> Queue<E> create() {
      return new SingleConsumerQueue<E>();
    }
  },
  EliminationStack() {
    @Override public <E> Queue<E> create() {
      return new EliminationStack<E>().asLifoQueue();
    }
  },
  ConcurrentLinkedQueue() {
    @Override public <E> Queue<E> create() {
      return new ConcurrentLinkedQueue<E>();
    }
  },
  ArrayBlockingQueue() {
    @Override public <E> Queue<E> create() {
      return new ArrayBlockingQueue<E>(10000);
    }
  },
  LinkedBlockingQueueBenchmark() {
    @Override public <E> Queue<E> create() {
      return new LinkedBlockingQueue<E>();
    }
  },
  LinkedTransferQueue() {
    @Override public <E> Queue<E> create() {
      return new LinkedTransferQueue<E>();
    }
  },
  SynchronousQueue() {
    @Override public <E> Queue<E> create() {
      return new SynchronousQueue<E>();
    }
  },
  SynchronizedArrayDeque() {
    @Override public <E> Queue<E> create() {
      return Queues.synchronizedDeque(new ArrayDeque<E>(10000));
    }
  };

  public abstract <E> Queue<E> create();
}
