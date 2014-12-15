/*
 * Copyright 2013 Ben Manes. All Rights Reserved.
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

import com.github.benmanes.caffeine.atomic.PaddedAtomicReference;

/**
 * A lock-free unbounded queue based on linked nodes that supports concurrent producers and is
 * restricted to a single consumer. This queue orders elements FIFO (first-in-first-out). The
 * <em>head</em> of the queue is that element that has been on the queue the longest time. The
 * <em>tail</em> of the queue is that element that has been on the queue the shortest time. New
 * elements are inserted at the tail of the queue, and the queue retrieval operations obtain
 * elements at the head of the queue. Like most other concurrent collection implementations, this
 * class does not permit the use of {@code null} elements.
 * <p>
 * A {@code SingleConsumerQueue} is an appropriate choice when many producers threads will share
 * access to a common collection and a single consumer thread drains it. This collection is useful
 * in scenarios that leverage techniques such as flat combining, actors, or lock amortization.
 * <p>
 * This implementation employs combination to transfer elements between threads that are producing
 * concurrently. This approach avoids contention on the queue by combining operations when the
 * colliding operations have identical semantics. When a pair of producers collide, the task of
 * performing the combined set of operations is delegated to one of the threads and the other
 * thread optionally waits for its operation to be performed. This decision of whether to be
 * <em>linearizable</em> or <em>optimistic</em> is determined when the queue is constructed.
 * <p>
 * Iterators are <i>weakly consistent</i>, returning elements reflecting the state of the stack at
 * some point at or since the creation of the iterator. They do <em>not</em> throw {@link
 * java.util.ConcurrentModificationException}, and may proceed concurrently with other operations.
 * Elements contained in the stack since the creation of the iterator will be returned exactly once.
 * <p>
 * Beware that it is the responsibility of the caller to ensure that a consumer has exclusive
 * access to the queue. This implementation does <em>not</em> include fail-fast behavior to guard
 * against incorrect consumer usage.
 * <p>
 * Beware that, unlike in most collections, the {@code size} method is <em>NOT</em> a
 * constant-time operation. Because of the asynchronous nature of these stacks, determining the
 * current number of elements requires a traversal of the elements, and so may report inaccurate
 * results if this collection is modified during traversal.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class SingleConsumerQueue<E> /* implements Queue<E> */ {

  /*
   * The queue is represented as a singly-linked list with an atomic head and tail reference. It is
   * and uses based on non-intrusive single-consumer / multi-producer node-based queue described by
   * Dmitriy Vyukov [1].
   *
   * The backoff strategy of combining operations with identical semantics is based on inverting
   * the elimination technique [1]. Elimination allows pairs of operations with reverse semantics,
   * like pushes and pops on a stack, to complete without any central coordination, and therefore
   * substantially aids scalability. The approach of applying elimination and reversing its
   * semantics was explored in [3, 4].
   *
   * This implementation borrows optimizations from {@link java.util.concurrent.Exchanger} for
   * choosing an arena location and awaiting a match [5]. To improve memory usage for scenarios
   * that may create thousands of queue instances, such as actor mailboxes, the arena is lazily
   * initialized when contention is detected.
   *
   * [1] Non-intrusive MPSC node-based queue
   * http://www.1024cores.net/home/lock-free-algorithms/queues/non-intrusive-mpsc-node-based-queue
   * [1] A Scalable Lock-free Stack Algorithm
   * http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.156.8728
   * [3] Using elimination to implement scalable and lock-free fifo queues
   * http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.108.6422
   * [4] A Dynamic Elimination-Combining Stack Algorithm
   * http://www.cs.bgu.ac.il/~hendlerd/papers/DECS.pdf
   * [5] A Scalable Elimination-based Exchange Channel
   * http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.59.7396
   */

  private final PaddedAtomicReference<Node<E>> head;
  private volatile Node<E> tail;

  public SingleConsumerQueue() {
    head = new PaddedAtomicReference<>();
    tail = new Node<E>(null);
  }

  public void offer(E e) {
    Node<E> node = new Node<E>(e);
    head.getAndSet(node).lazySet(node);
  }

  public E poll() {
    for (;;) {
      Node<E> next = tail.next;
      if (next != null) {
        tail = next;
        E e = next.value;
        next = null;
        return e;
      }
    }
  }

  static final class Node<E> {
    private Node<E> next;
    private final E value;

    Node(E value) {
      // TODO(ben):
      // Uses relaxed write because item can only be seen after publication
      // UnsafeAccess.UNSAFE.putObject(this, ...);
      this.value = value;
    }

    void lazySet(Node<E> node) {
      // TODO(ben): lazy set
      next = node;
    }
  }
}
