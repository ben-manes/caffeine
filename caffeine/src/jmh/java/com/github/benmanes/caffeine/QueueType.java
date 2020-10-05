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
import java.util.function.Supplier;

import org.jctools.queues.MpscLinkedQueue;

import com.google.common.collect.Queues;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings({"ImmutableEnumChecker", "deprecation"})
public enum QueueType {
  MpscLinkedQueue(MpscLinkedQueue::new),
  SingleConsumerQueue_optimistic(SingleConsumerQueue::optimistic),
  SingleConsumerQueue_linearizable(SingleConsumerQueue::linearizable),
  ConcurrentLinkedQueue(ConcurrentLinkedQueue<Object>::new),
  ArrayBlockingQueue(() -> new ArrayBlockingQueue<>(10000)),
  LinkedBlockingQueue(LinkedBlockingQueue<Object>::new),
  LinkedTransferQueue(LinkedTransferQueue<Object>::new),
  SynchronousQueue(SynchronousQueue<Object>::new),
  SynchronizedArrayDeque(() -> Queues.synchronizedDeque(new ArrayDeque<>(10000)));

  private final Supplier<Queue<Object>> factory;

  private QueueType(Supplier<Queue<Object>> factory) {
    this.factory = factory;
  }

  @SuppressWarnings("unchecked")
  public <E> Queue<E> create() {
    return (Queue<E>) factory.get();
  }
}
