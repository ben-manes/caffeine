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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;

import javax.annotation.Nullable;

import sun.misc.Contended;

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
 * @see https://github.com/ben-manes/caffeine
 * @param <E> the type of elements held in this collection
 */
public final class SingleConsumerQueue<E> implements Queue<E> {

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

  final static long HEAD_OFFSET =
      UnsafeAccess.objectFieldOffset(SingleConsumerQueue.class, "head");

  final static long TAIL_OFFSET =
      UnsafeAccess.objectFieldOffset(SingleConsumerQueue.class, "tail");

  volatile Node<E> head;
  volatile Node<E> tail;

  public SingleConsumerQueue() {
    // Uses relaxed writes because these fields can only be seen after publication
    Node<E> node = new Node<E>(null);
    lazySetHead(node);
    lazySetTail(node);
  }

  void lazySetHead(Node<E> next) {
    UnsafeAccess.UNSAFE.putOrderedObject(this, HEAD_OFFSET, next);
  }

  boolean casHead(Node<E> expect, Node<E> update) {
    return UnsafeAccess.UNSAFE.compareAndSwapObject(this, HEAD_OFFSET, expect, update);
  }

  void lazySetTail(Node<E> next) {
    UnsafeAccess.UNSAFE.putOrderedObject(this, TAIL_OFFSET, next);
  }

  @Override
  public boolean isEmpty() {
    return (head == tail);
  }

  @Override
  public int size() {
    Node<E> t = tail;

    // Uses relaxed reads as `next` is lazily set and accessing the tail issued a load barrier
    Node<E> cursor = t.getNextRelaxed();
    int size = 0;
    while (cursor != null) {
      cursor = cursor.getNextRelaxed();
      size++;
    }
    return size;
  }

  @Override
  public void clear() {
    tail = head;
  }

  @Override
  public boolean contains(Object o) {
    if (o == null) {
      return false;
    }

    Node<E> cursor = tail.getNextRelaxed();
    while (cursor != null) {
      if (o.equals(cursor.value)) {
        return true;
      }
      cursor = cursor.next;
    }
    return false;
  }

  @Override
  public boolean containsAll(Collection<?> c) {
    Objects.requireNonNull(c);
    for (Object e : c) {
      if (!contains(e)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public E peek() {
    Node<E> next = tail.getNextRelaxed();
    return (next == null) ? null : next.value;
  }

  @Override
  public E element() {
    E e = peek();
    if (e == null) {
      throw new NoSuchElementException();
    }
    return e;
  }

  @Override
  public boolean offer(E e) {
    Objects.requireNonNull(e);
    return offer(new Node<E>(e));
  }

  /** Inserts the node, which may result in a batch insertion if the node has a link chain. */
  boolean offer(Node<E> node) {
    for (;;) {
      Node<E> h = head;
      if (casHead(h, node)) {
        h.lazySetNext(node);
        return true;
      }

      // TODO(ben): Add combining backoff
    }
  }

  @Override
  public E poll() {
    Node<E> next = tail.getNextRelaxed();
    if (next == null) {
      return null;
    }
    lazySetTail(next);
    E e = next.value;
    next.value = null;
    return e;
  }

  @Override
  public boolean add(E e) {
    return offer(e);
  }

  @Override
  public boolean addAll(Collection<? extends E> c) {
    Objects.requireNonNull(c);

    Node<E> node = null;
    for (E e : c) {
      node = new Node<E>(e, node);
    }
    if (node == null) {
      return false;
    }
    return offer(node);
  }

  @Override
  public E remove() {
    E e = poll();
    if (e == null) {
      throw new NoSuchElementException();
    }
    return e;
  }

  @Override
  public boolean remove(Object o) {
    Objects.requireNonNull(o);

    Node<E> prev = tail;
    Node<E> cursor = prev.getNextRelaxed();
    while (cursor != null) {
      if (o.equals(cursor.value)) {
        prev.lazySetNext(cursor.next);
        return true;
      }
      prev = cursor;
      cursor = prev.getNextRelaxed();
    }
    return false;
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    return removeByPresentce(c, false);
  }

  @Override
  public boolean retainAll(Collection<?> c) {
    return removeByPresentce(c, true);
  }

  /**
   * Removes elements based on whether they are also present in the provided collection.
   *
   * @param c collection containing elements to keep or discard
   * @param retain whether to retain only or remove only elements present in both collections
   */
  boolean removeByPresentce(Collection<?> c, boolean retain) {
    Objects.requireNonNull(c);

    Node<E> prev = tail;
    Node<E> cursor = prev.getNextRelaxed();
    boolean modified = false;
    while (cursor != null) {
      boolean present = c.contains(cursor.value);
      if (present == retain) {
        prev.lazySetNext(cursor.next);
        modified = true;
      }
      prev = cursor;
      cursor = prev.getNextRelaxed();
    }
    return modified;
  }

  @Override
  public Iterator<E> iterator() {
    return new Iterator<E>() {
      Node<E> cursor = tail.getNextRelaxed();
      Node<E> prev = null;
      Node<E> next = (cursor == null) ? null : cursor.getNextRelaxed();

      @Override public boolean hasNext() {
        return (cursor != null);
      }

      @Override public E next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        E e = cursor.value;
        prev = cursor;
        cursor = next;
        next = (cursor == null) ? null : cursor.getNextRelaxed();
        return e;
      }

      @Override public void remove() {
        if (prev == null) {
          throw new IllegalStateException();
        }
        prev.lazySetNext(next);
        prev = null;
        cursor = next;
        next = cursor.getNextRelaxed();
      }
    };
  }

  @Override
  public Object[] toArray() {
    List<E> list = new ArrayList<E>();
    for (E e : this) {
      list.add(e);
    }
    return list.toArray();
  }

  @Override
  public <T> T[] toArray(T[] a) {
    List<E> list = new ArrayList<E>();
    for (E e : this) {
      list.add(e);
    }
    return list.toArray(a);
  }

  @Override
  public String toString() {
    Iterator<E> it = iterator();
    if (!it.hasNext()) {
      return "[]";
    }

    StringBuilder sb = new StringBuilder();
    sb.append('[');
    for (;;) {
      E e = it.next();
      sb.append(e == this ? "(this Collection)" : e);
      if (!it.hasNext()) {
        return sb.append(']').toString();
      }
      sb.append(',').append(' ');
    }
  }

  @Contended
  static final class Node<E> {
    final static long NEXT_OFFSET = UnsafeAccess.objectFieldOffset(Node.class, "next");

    Node<E> next;
    E value;

    Node(@Nullable E value, @Nullable Node<E> next) {
      this(value);
      this.next = next;
    }

    Node(@Nullable E value) {
      this.value = value;
    }

    @SuppressWarnings("unchecked")
    @Nullable Node<E> getNextRelaxed() {
      return (Node<E>) UnsafeAccess.UNSAFE.getObject(this, NEXT_OFFSET);
    }

    void lazySetNext(@Nullable Node<E> newNext) {
      UnsafeAccess.UNSAFE.putOrderedObject(this, NEXT_OFFSET, newNext);
    }
  }
}
