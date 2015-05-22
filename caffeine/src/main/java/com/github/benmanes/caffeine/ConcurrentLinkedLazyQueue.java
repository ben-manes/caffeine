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
package com.github.benmanes.caffeine;

import static java.util.Objects.requireNonNull;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.github.benmanes.caffeine.ConcurrentLinkedLazyQueue.Node;
import com.github.benmanes.caffeine.base.UnsafeAccess;

/**
 * A lock-free unbounded queue based on linked nodes. This queue orders elements FIFO
 * (first-in-first-out). The <em>head</em> of the queue is that element that has been on the queue
 * the longest time. The <em>tail</em> of the queue is that element that has been on the queue the
 * shortest time. New elements are inserted at the tail of the queue, and the queue retrieval
 * operations obtain elements at the head of the queue. Like most other concurrent collection
 * implementations, this class does not permit the use of {@code null} elements.
 * <p>
 * This implementation employs combination to transfer elements between threads that are producing
 * concurrently. This approach avoids contention on the queue by combining colliding operations that
 * have identical semantics. When a pair of producers collide, the task of performing the combined
 * set of operations is delegated to one of the threads and the other thread optionally waits for
 * its operation to be completed. This decision of whether to wait for completion is determined by
 * constructing either a <em>linearizable</em> or <em>optimistic</em> queue.
 * <p>
 * Iterators are <i>weakly consistent</i>, returning elements reflecting the state of the queue at
 * some point at or since the creation of the iterator. They do <em>not</em> throw
 * {@link java.util.ConcurrentModificationException}, and may proceed concurrently with other
 * operations. Elements contained in the queue since the creation of the iterator will be returned
 * exactly once.
 * <p>
 * Beware that, unlike in most collections, the {@code size} method is <em>NOT</em> a constant-time
 * operation. Because of the asynchronous nature of these queues, determining the current number of
 * elements requires a traversal of the elements, and so may report inaccurate results if this
 * collection is modified during traversal.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @param <E> the type of elements held in this collection
 */
@Beta
final class ConcurrentLinkedLazyQueue<E> extends CLLQHeader.HeadAndTailRef<E>
    implements Queue<E>, Serializable {

  /*
   * The queue is represented as a doubly-linked list with an atomic head and tail reference. It is
   * based on the optimistic linked queue algorithm [1].
   *
   * The backoff strategy of combining operations with identical semantics is based on inverting
   * the elimination technique [2]. Elimination allows pairs of operations with reverse semantics,
   * like pushes and pops on a stack, to complete without any central coordination and therefore
   * substantially aids scalability. The approach of applying elimination and reversing its
   * semantics was explored in [3, 4]. Unlike other approaches, this implementation does not use
   * opcodes or a background thread.
   *
   * This implementation borrows optimizations from {@link java.util.concurrent.Exchanger} for
   * choosing an arena location and awaiting a match [5].
   *
   * [1] An Optimistic Approach to Lock-Free FIFO Queues
   * http://people.csail.mit.edu/edya/publications/OptimisticFIFOQueue-journal.pdf
   * [2] A Scalable Lock-free Stack Algorithm
   * http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.156.8728
   * [3] Using Elimination to Implement Scalable and Lock-Free FIFO Queues
   * http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.108.6422
   * [4] A Dynamic Elimination-Combining Stack Algorithm
   * http://www.cs.bgu.ac.il/~hendlerd/papers/DECS.pdf
   * [5] A Scalable Elimination-based Exchange Channel
   * http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.59.7396
   */

  /** The number of CPUs */
  static final int NCPU = Runtime.getRuntime().availableProcessors();

  /** The number of slots in the elimination array. */
  static final int ARENA_LENGTH = ceilingNextPowerOfTwo((NCPU + 1) / 2);

  /** The mask value for indexing into the arena. */
  static final int ARENA_MASK = ARENA_LENGTH - 1;

  /**
   * The number of times to spin (doing nothing except polling a memory location) before giving up
   * while waiting to eliminate an operation. Should be zero on uniprocessors. On multiprocessors,
   * this value should be large enough so that two threads exchanging items as fast as possible
   * block only when one of them is stalled (due to GC or preemption), but not much longer, to avoid
   * wasting CPU resources. Seen differently, this value is a little over half the number of cycles
   * of an average context switch time on most systems. The value here is approximately the average
   * of those across a range of tested systems.
   */
  static final int SPINS = (NCPU == 1) ? 0 : 2000;

  /** The offset to the thread-specific probe field. */
  static final long PROBE = UnsafeAccess.objectFieldOffset(Thread.class, "threadLocalRandomProbe");

  static int ceilingNextPowerOfTwo(int x) {
    // From Hacker's Delight, Chapter 3, Harry S. Warren Jr.
    return 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(x - 1));
  }

  final AtomicReference<Node<E>>[] arena;
  final Function<E, Node<E>> factory;

  @SuppressWarnings({"unchecked", "rawtypes"})
  private ConcurrentLinkedLazyQueue(Function<E, Node<E>> factory) {
    arena = new AtomicReference[ARENA_LENGTH];
    for (int i = 0; i < ARENA_LENGTH; i++) {
      arena[i] = new AtomicReference<>();
    }
    Node<E> node = new Node<E>(null);
    this.factory = factory;
    lazySetHead(node);
    lazySetTail(node);
  }

  /**
   * Creates a queue with an optimistic backoff strategy. A thread completes its operation
   * without waiting after it successfully hands off the additional element(s) to another producing
   * thread for batch insertion. This optimistic behavior may result in additions not appearing in
   * FIFO order due to the backoff strategy trying to compensate for queue contention.
   *
   * @param <E> the type of elements held in this collection
   * @return a new queue where producers complete their operation immediately if combined with
   *         another producing thread's
   */
  public static <E> ConcurrentLinkedLazyQueue<E> optimistic() {
    return new ConcurrentLinkedLazyQueue<>(Node<E>::new);
  }

  /**
   * Creates a queue with a linearizable backoff strategy. A thread waits for a completion
   * signal if it successfully hands off the additional element(s) to another producing
   * thread for batch insertion.
   *
   * @param <E> the type of elements held in this collection
   * @return a new queue where producers wait for a completion signal after combining its addition
   *         with another producing thread's
   */
  public static <E> ConcurrentLinkedLazyQueue<E> linearizable() {
    return new ConcurrentLinkedLazyQueue<>(LinearizableNode<E>::new);
  }

  @Override
  public boolean isEmpty() {
    return (head == tail);
  }

  @Override
  public int size() {
    int size = 0;
    Node<E> h = head;
    Node<E> cursor = tail;
    while ((cursor != h) && (size != Integer.MAX_VALUE)) {
      Node<E> prev = cursor.getPrevRelaxed();
//      if (prev.getNextRelaxed() == null) {
//        prev.lazySetNext(cursor);
//      }
      cursor = prev;
      size++;
    }
    return size;
  }

  @Override
  public void clear() {
    head = tail;
  }

  @Override
  public boolean contains(Object o) {
    if (o == null) {
      return false;
    }
    for (Iterator<E> it = iterator(); it.hasNext();) {
      if (o.equals(it.next())) {
        return true;
      }
    }
    return false;
  }

  @Override
  public E peek() {
    Node<E> h = head;
    Node<E> t = tail;
    if (h == t) {
      return null;
    }
    Node<E> next = h.getNextRelaxed();
    if (next == null) {
      while ((next = h.next) == null) {}
    }
    return next.value;
  }

  @Override
  public boolean offer(E e) {
    requireNonNull(e);

    Node<E> node = factory.apply(e);
    append(node, node);
    return true;
  }

  @Override
  public E poll() {
    for (;;) {
      Node<E> h = head;
      Node<E> t = tail;
      Node<E> next = h.getNextRelaxed();
      if (h == t) {
        return null;
      }
      if (next == null) {
        fixup(h, t);
        continue;
      }
      if (casHead(h, next)) {
        E e = next.value;
        next.value = null;
        h.lazySetPrev(null);
        h.lazySetNext(null);
        h.complete();
        return e;
      }
    }
  }

  /** Fix the backwords pointers when needed. */
  static <E> void fixup(Node<E> h, Node<E> t) {
    Node<E> node;
    Node<E> cursor = t;
    for (;;) {
      node = cursor.prev;
      if (node == null) {
        break;
      }
      node.next = cursor;
      cursor = node;
    }
  }

  /**
   * Removes all available elements from this queue and returns an unmodifiable view of the removed
   * elements. This operation is more efficient than repeatedly polling this queue.
   *
   * @return the elements removed from the queue
   */
  public Collection<E> drain() {
    for (;;) {
      Node<E> h = head;
      Node<E> t = tail;

      if (h == t) {
        return Collections.emptyList();
      } else if (casHead(h, t)) {
        return new CollectionView<E>(h, t);
      }
    }
  }

  @Override
  public boolean add(E e) {
    return offer(e);
  }

  @Override
  public boolean addAll(Collection<? extends E> c) {
    requireNonNull(c);

    Node<E> first = null;
    Node<E> last = null;
    for (E e : c) {
      requireNonNull(e);
      if (first == null) {
        first = factory.apply(e);
        last = first;
      } else {
        Node<E> newLast = new Node<E>(e);
        newLast.lazySetPrev(last);
        last.lazySetNext(newLast);
        last = newLast;
      }
    }
    if (first == null) {
      return false;
    }
    append(first, last);
    return true;
  }

  /** Adds the linked list of nodes to the queue. */
  void append(@Nonnull Node<E> first, @Nonnull Node<E> last) {
    for (;;) {
      Node<E> t = tail;
      first.lazySetPrev(t);
      if (casTail(t, last)) {
        t.lazySetNext(first);
        for (;;) {
          first.complete();
          if (first == last) {
            return;
          }
          Node<E> next = first.getNextRelaxed();
          if (next == null) {
            break;
          }
          first = next;
        }
      }
      first.lazySetPrev(null);
      Node<E> node = transferOrCombine(first, last);
      if (node == null) {
        first.await();
        return;
      } else if (node != first) {
        last = node;
      }
    }
  }

  /**
   * Attempts to receive a linked list from a waiting producer or transfer the specified linked list
   * to an arriving producer.
   *
   * @param first the first node in the linked list to try to transfer
   * @param last the last node in the linked list to try to transfer
   * @return either {@code null} if the element was transferred, the first node if neither a
   *         transfer nor receive were successful, or the received last element from a producer
   */
  @Nullable Node<E> transferOrCombine(@Nonnull Node<E> first, Node<E> last) {
    int index = index();
    AtomicReference<Node<E>> slot = arena[index];

    for (;;) {
      Node<E> found = slot.get();
      if (found == null) {
        if (slot.compareAndSet(null, first)) {
          for (int spin = 0; spin < SPINS; spin++) {
            if (slot.get() != first) {
              return null;
            }
          }
          return slot.compareAndSet(first, null) ? first : null;
        }
      } else if (slot.compareAndSet(found, null)) {
        found.lazySetPrev(last);
        last.lazySetNext(found);
        last = findLast(found);
        for (int i = 1; i < ARENA_LENGTH; i++) {
          slot = arena[(i + index) & ARENA_MASK];
          found = slot.get();
          if ((found != null) && slot.compareAndSet(found, null)) {
            found.lazySetPrev(last);
            last.lazySetNext(found);
            last = findLast(found);
          }
        }
        return last;
      }
    }
  }

  /** Returns the arena index for the current thread. */
  static final int index() {
    int probe = UnsafeAccess.UNSAFE.getInt(Thread.currentThread(), PROBE);
    if (probe == 0) {
      ThreadLocalRandom.current(); // force initialization
      probe = UnsafeAccess.UNSAFE.getInt(Thread.currentThread(), PROBE);
    }
    return (probe & ARENA_MASK);
  }

  /** Returns the last node in the linked list. */
  @Nonnull static <E> Node<E> findLast(@Nonnull Node<E> node) {
    Node<E> next;
    while ((next = node.getNextRelaxed()) != null) {
      node = next;
    }
    return node;
  }

  @Override
  public Iterator<E> iterator() {
    return new Iterator<E>() {
      Node<E> t = tail;
      Node<E> prev = null;
      Node<E> cursor = head;
      boolean failOnRemoval = true;

      @Override
      public boolean hasNext() {
        return (cursor != t);
      }

      @Override
      public E next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        advance();
        failOnRemoval = false;
        return cursor.value;
      }

      private void advance() {
        if (cursor.getNextRelaxed() == null) {
          while (cursor.next == null) {}
        }

        if ((prev == null) || !failOnRemoval) {
          prev = cursor;
        }
        cursor = cursor.getNextRelaxed();
      }

      @Override
      public void remove() {
        if (failOnRemoval) {
          throw new IllegalStateException();
        }
        for (;;) {
          if (cursor == tail) {
            Node<E> p = cursor.prev;
            if (casTail(cursor, p)) {
              p.lazySetNext(null);
              cursor = t;
              break;
            }
          } else if (cursor.getNextRelaxed() == null) {
            fixup(head, tail);
          } else {
            Node<E> p = cursor.prev;
            Node<E> n = cursor.next;
            if (n.casPrev(cursor, p)) {
              if (!p.casNext(cursor, n)) {
                p.lazySetNext(null);
              }
            }
            break;
          }
        }
        failOnRemoval = true;
      }
    };
  }

  /* ---------------- Serialization Support -------------- */

  static final long serialVersionUID = 1;

  Object writeReplace() {
    return new SerializationProxy<E>(this);
  }

  private void readObject(ObjectInputStream stream) throws InvalidObjectException {
    throw new InvalidObjectException("Proxy required");
  }

  /** A proxy that is serialized instead of the queue. */
  static final class SerializationProxy<E> implements Serializable {
    final boolean linearizable;
    final List<E> elements;

    SerializationProxy(ConcurrentLinkedLazyQueue<E> queue) {
      linearizable = (queue.factory.apply(null) instanceof LinearizableNode<?>);
      elements = new ArrayList<>(queue);
    }

    Object readResolve() {
      ConcurrentLinkedLazyQueue<E> queue = linearizable ? linearizable() : optimistic();
      queue.addAll(elements);
      return queue;
    }

    static final long serialVersionUID = 1;
  }

  /** A view of the linked nodes as an unmodifiable collection. */
  static final class CollectionView<E> extends AbstractCollection<E> implements Serializable {
    private static final long serialVersionUID = 1L;

    final Node<E> h;
    final Node<E> t;

    CollectionView(Node<E> h, Node<E> t) {
      this.h = h;
      this.t = t;
    }

    @Override
    public int size() {
      int size = 0;
      Node<E> cursor = t;
      while (cursor != h) {
        cursor = cursor.prev;
        size++;
      }
      return size;
    }

    @Override
    public Iterator<E> iterator() {
      return new Iterator<E>() {
        Node<E> cursor = h;

        @Override
        public boolean hasNext() {
          return (cursor != t);
        }

        @Override
        public E next() {
          if (!hasNext()) {
            throw new NoSuchElementException();
          }
          advance();
          return cursor.value;
        }

        private void advance() {
          if (cursor.next == null) {
            fixup(h, t);
          }
          cursor = cursor.getNextRelaxed();
        }
      };
    }

    Object writeReplace() {
      return Collections.unmodifiableCollection(new ArrayList<>(this));
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }
  }

  static class Node<E> {
    static final long PREV_OFFSET = UnsafeAccess.objectFieldOffset(Node.class, "prev");
    static final long NEXT_OFFSET = UnsafeAccess.objectFieldOffset(Node.class, "next");

    volatile Node<E> prev;
    volatile Node<E> next;
    E value;

    Node(@Nullable E value) {
      this.value = value;
    }

    @SuppressWarnings("unchecked")
    @Nullable Node<E> getPrevRelaxed() {
      return (Node<E>) UnsafeAccess.UNSAFE.getObject(this, PREV_OFFSET);
    }

    void lazySetPrev(@Nullable Node<E> node) {
      UnsafeAccess.UNSAFE.putOrderedObject(this, PREV_OFFSET, node);
    }

    boolean casPrev(@Nullable Node<E> expect, @Nullable Node<E> update) {
      return UnsafeAccess.UNSAFE.compareAndSwapObject(this, PREV_OFFSET, expect, update);
    }

    @SuppressWarnings("unchecked")
    @Nullable Node<E> getNextRelaxed() {
      return (Node<E>) UnsafeAccess.UNSAFE.getObject(this, NEXT_OFFSET);
    }

    void lazySetNext(@Nullable Node<E> node) {
      UnsafeAccess.UNSAFE.putOrderedObject(this, NEXT_OFFSET, node);
    }

    boolean casNext(@Nullable Node<E> expect, @Nullable Node<E> update) {
      return UnsafeAccess.UNSAFE.compareAndSwapObject(this, NEXT_OFFSET, expect, update);
    }

    /** A no-op notification that the element was added to the queue. */
    void complete() {}

    /** A no-op wait until the operation has completed. */
    void await() {}

    /** Always returns that the operation completed. */
    boolean isDone() {
      return true;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "[" + value + "]";
    }
  }

  static final class LinearizableNode<E> extends Node<E> {
    volatile boolean done;

    LinearizableNode(@Nullable E value) {
      super(value);
    }

    /** A notification that the element was added to the queue. */
    @Override
    void complete() {
      done = true;
    }

    /** A busy wait until the operation has completed. */
    @Override
    void await() {
      while (!done) {};
    }

    /** Returns whether the operation completed. */
    @Override
    boolean isDone() {
      return done;
    }
  }
}

/** The namespace for field padding through inheritance. */
final class CLLQHeader {
  abstract static class PadHead<E> extends AbstractQueue<E> {
    long p00, p01, p02, p03, p04, p05, p06, p07;
    long p10, p11, p12, p13, p14, p15, p16, p17;
  }

  /** Enforces a memory layout to avoid false sharing by padding the head node. */
  abstract static class HeadRef<E> extends PadHead<E> {
    static final long HEAD_OFFSET = UnsafeAccess.objectFieldOffset(HeadRef.class, "head");

    volatile Node<E> head;

    void lazySetHead(Node<E> node) {
      UnsafeAccess.UNSAFE.putOrderedObject(this, HEAD_OFFSET, node);
    }

    boolean casHead(Node<E> expect, Node<E> update) {
      return UnsafeAccess.UNSAFE.compareAndSwapObject(this, HEAD_OFFSET, expect, update);
    }
  }

  abstract static class PadHeadAndTail<E> extends HeadRef<E> {
    long p20, p21, p22, p23, p24, p25, p26, p27;
    long p30, p31, p32, p33, p34, p35, p36, p37;
  }

  /** Enforces a memory layout to avoid false sharing by padding the tail node. */
  abstract static class HeadAndTailRef<E> extends PadHeadAndTail<E> {
    static final long TAIL_OFFSET = UnsafeAccess.objectFieldOffset(HeadAndTailRef.class, "tail");

    volatile Node<E> tail;

    void lazySetTail(Node<E> node) {
      UnsafeAccess.UNSAFE.putOrderedObject(this, TAIL_OFFSET, node);
    }

    boolean casTail(Node<E> expect, Node<E> update) {
      return UnsafeAccess.UNSAFE.compareAndSwapObject(this, TAIL_OFFSET, expect, update);
    }
  }
}
