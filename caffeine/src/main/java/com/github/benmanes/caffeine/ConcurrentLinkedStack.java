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
import javax.annotation.concurrent.ThreadSafe;

import com.github.benmanes.caffeine.ConcurrentLinkedStack.Node;
import com.github.benmanes.caffeine.base.UnsafeAccess;

/**
 * An unbounded thread-safe stack based on linked nodes. This stack orders elements LIFO
 * (last-in-first-out). The <em>top</em> of the stack is that element that has been on the stack the
 * shortest time. New elements are inserted at and retrieved from the top of the stack. A
 * {@code ConcurrentLinkedStack} is an appropriate choice when many threads will exchange elements
 * through shared access to a common collection. Like most other concurrent collection
 * implementations, this class does not permit the use of {@code null} elements.
 * <p>
 * This implementation employs elimination and combination to transfer elements between threads that
 * are pushing and popping concurrently. This technique avoids contention on the stack by attempting
 * to cancel opposing operations and merge additive operations if an immediate update to the stack
 * is not successful. When a pair of additive operations collide, the task of performing the
 * combined set of operations is delegated to one of the threads and the other thread optionally
 * waits for its operation to be completed. This decision of whether to wait for completion is
 * determined by constructing either a <em>linearizable</em> or <em>optimistic</em> stack.
 * <p>
 * Iterators are <i>weakly consistent</i>, returning elements reflecting the state of the stack at
 * some point at or since the creation of the iterator. They do <em>not</em> throw
 * {@link java.util.ConcurrentModificationException}, and may proceed concurrently with other
 * operations. Elements contained in the stack since the creation of the iterator will be returned
 * exactly once.
 * <p>
 * Beware that, unlike in most collections, the {@code size} method is <em>NOT</em> a constant-time
 * operation. Because of the asynchronous nature of these stacks, determining the current number of
 * elements requires a traversal of the elements, and so may report inaccurate results if this
 * collection is modified during traversal.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @param <E> the type of elements held in this collection
 */
@Beta
@ThreadSafe
public final class ConcurrentLinkedStack<E> extends CLSHeader.TopRef<E> implements Serializable {

  /*
   * A Treiber's stack is represented as a singly-linked list with an atomic top reference. To
   * support arbitrary deletion (remove(Object x)), rather than just lazily nulling out nodes and
   * skipping them when they reach top, we detect and relink around nodes as they are removed. This
   * is still partially lazy and multiple adjacent concurrent relinks may leave nulls in place, so
   * all traversals must detect and relink lingering nulls.
   *
   * The stack is augmented with an elimination-combining array to minimize the top reference from
   * becoming a sequential bottleneck. Elimination allows pairs of operations with reverse
   * semantics, like pushes and pops on a stack, to complete without any central coordination, and
   * therefore substantially aids scalability [1, 2]. Combining allows pairs of operations with
   * identical semantics, specifically pushes on a stack, to batch the work and therefore reduces
   * the number of threads updating the top reference. The approach to dynamically eliminate and
   * combine operations is explored in [3, 4]. Unlike other approaches, this implementation does not
   * use opcodes or a background thread and instead allows consumers to assist in adding to the
   * stack.
   *
   * This implementation borrows optimizations from {@link java.util.concurrent.Exchanger} for
   * choosing an arena location and awaiting a match [5].
   *
   * [1] A Scalable Lock-free Stack Algorithm
   * http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.156.8728
   * [2] Concurrent Data Structures
   * http://www.cs.tau.ac.il/~shanir/concurrent-data-structures.pdf
   * [3] A Dynamic Elimination-Combining Stack Algorithm
   * http://www.cs.bgu.ac.il/~hendlerd/papers/DECS.pdf
   * [4] Using Elimination and Delegation to Implement a Scalable NUMA-Friendly Stack
   * http://cs.brown.edu/~irina/papers/11431-hotpar13-calciu.pdf
   * [5] A Scalable Elimination-based Exchange Channel
   * http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.59.7396
   */

  /** The number of CPUs */
  static final int NCPU = Runtime.getRuntime().availableProcessors();

  /** The number of slots in the elimination array. */
  static final int ARENA_LENGTH = ceilingNextPowerOfTwo((NCPU + 1) / 2);

  /** The mask value for indexing into the arena. */
  static int ARENA_MASK = ARENA_LENGTH - 1;

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

  /** Creates a {@code EliminationStack} that is initially empty. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private ConcurrentLinkedStack(Function<E, Node<E>> factory) {
    arena = new AtomicReference[ARENA_LENGTH];
    for (int i = 0; i < ARENA_LENGTH; i++) {
      arena[i] = new AtomicReference<>();
    }
    this.factory = factory;
  }

  /**
   * Creates a stack with an optimistic backoff strategy. A thread completes its operation without
   * waiting after it successfully hands off the additional element(s) to another producing thread
   * for batch insertion. This optimistic behavior may result in additions not appearing in LIFO
   * order due to the backoff strategy trying to compensate for stack contention.
   *
   * @param <E> the type of elements held in this collection
   * @return a new stack where producers complete their operation immediately if combined with
   *         another thread's
   */
  public static <E> ConcurrentLinkedStack<E> optimistic() {
    return new ConcurrentLinkedStack<>(Node<E>::new);
  }

  /**
   * Creates a stack with a linearizable backoff strategy. A thread waits for a completion signal if
   * it successfully hands off the additional element(s) to another producing thread for batch
   * insertion.
   *
   * @param <E> the type of elements held in this collection
   * @return a new stack where producers wait for a completion signal after combining its addition
   *         with another thread's
   */
  public static <E> ConcurrentLinkedStack<E> linearizable() {
    return new ConcurrentLinkedStack<>(LinearizableNode<E>::new);
  }

  @Override
  public boolean isEmpty() {
    for (;;) {
      Node<E> node = top;
      if (node == null) {
        return true;
      }
      E e = node.get();
      if (e == null) {
        casTop(node, node.next);
      } else {
        return false;
      }
    }
  }

  /**
   * Returns the number of elements in this stack.
   * <p>
   * Beware that, unlike in most collections, this method is <em>NOT</em> a constant-time
   * operation. Because of the asynchronous nature of these stacks, determining the current
   * number of elements requires an O(n) traversal. Additionally, if elements are added or
   * removed during execution of this method, the returned result may be inaccurate.  Thus,
   * this method is typically not very useful in concurrent applications.
   *
   * @return the number of elements in this stack
   */
  @Override
  public int size() {
    int size = 0;
    Node<E> node = top;
    Node<E> prev = null;
    while (node != null) {
      if (node.get() == null) {
        unlink(prev, node);
      } else {
        prev = node;
        size++;
      }
      node = node.next;
    }
    return size;
  }

  @Override
  public void clear() {
    Node<E> t = top;
    top = null;

    while (t != null) {
      t.lazySet(null);
      t = t.next;
    }
  }

  @Override
  public boolean contains(@Nullable Object o) {
    if (o == null) {
      return false;
    }

    Node<E> node = top;
    Node<E> prev = null;
    while (node != null) {
      E value = node.get();
      if (value == null) {
        unlink(prev, node);
      } else if (o.equals(value)) {
        return true;
      } else {
        prev = node;
      }
      node = node.next;
    }
    return false;
  }

  /**
   * Retrieves, but does not remove, the top of the stack (in other words, the last element pushed),
   * or returns {@code null} if this stack is empty.
   *
   * @return the top of the stack or {@code null} if this stack is empty
   */
  @Nullable
  public E peek() {
    for (;;) {
      Node<E> node = top;
      if (node == null) {
        return null;
      }
      E e = node.get();
      if (e == null) {
        casTop(node, node.next);
      } else {
        return e;
      }
    }
  }

  /**
   * Removes and returns the top element or returns {@code null} if this stack is empty.
   *
   * @return the top of this stack, or {@code null} if this stack is empty
   */
  @Nullable
  public E pop() {
    for (;;) {
      Node<E> current = top;
      if (current == null) {
        return null;
      }

      if (casTop(current, current.next)) {
        E e = current.get();
        if (e == null) {
          continue;
        }
        current.lazySet(null);
        return e;
      }
      Node<E> node = tryReceive();
      if (node != null) {
        return node.get();
      }
    }
  }

  /**
   * Attempts to receive a node from a waiting producer, spinning until one arrives within a fixed
   * duration. If the producer transfers a linked list of nodes, the first is consumed and the
   * remainder is produced back to the list.
   *
   * @return the consumed node or {@code null} if none was received
   */
  @Nullable Node<E> tryReceive() {
    int index = index();
    AtomicReference<Node<E>> slot = arena[index];

    for (int spin = 0; spin < SPINS; spin++) {
      Node<E> found = slot.get();
      if ((found != null) && slot.compareAndSet(found, null)) {
        found.complete();
        Node<E> next = found.next;
        if (next != null) {
          append(next, findLast(next));
        }
        return found;
      }
    }
    return null;
  }

  /**
   * Pushes an element onto the stack (in other words, adds an element at the top of this stack).
   *
   * @param e the element to push
   */
  public void push(@Nonnull E e) {
    requireNonNull(e);

    Node<E> node = factory.apply(e);
    append(node, node);
  }

  /** Adds the linked list of nodes to the stack. */
  void append(Node<E> first, Node<E> last) {
    for (;;) {
      last.next = top;

      if (casTop(last.next, first)) {
        for (;;) {
          first.complete();
          if (first == last) {
            return;
          }
          first = first.next;
        }
      }
      last.next = null;
      Node<E> node = transferOrCombine(first, last);
      if (node == null) {
        last.await();
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
  @Nullable Node<E> transferOrCombine(Node<E> first, Node<E> last) {
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
        last.next = found;
        last = findLast(found);
        for (int i = 1; i < ARENA_LENGTH; i++) {
          slot = arena[(i + index) & ARENA_MASK];
          found = slot.get();
          if ((found != null) && slot.compareAndSet(found, null)) {
            last.next = found;
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
    while ((next = node.next) != null) {
      node = next;
    }
    return node;
  }

  @Override
  public boolean add(E e) {
    push(e);
    return true;
  }

  @Override
  public boolean addAll(Collection<? extends E> c) {
    requireNonNull(c);

    Node<E> first = null;
    Node<E> last = null;
    for (E e : c) {
      requireNonNull(e);
      if (last == null) {
        last = factory.apply(e);
        first = last;
      } else {
        Node<E> newFirst = new Node<>(e);
        newFirst.next = first;
        first = newFirst;
      }
    }
    if (first == null) {
      return false;
    }
    append(first, last);
    return true;
  }

  @Override
  public boolean remove(Object o) {
    if (o == null) {
      return false;
    }

    Node<E> node = top;
    Node<E> prev = null;
    while (node != null) {
      E value = node.get();
      if (value == null) {
        unlink(prev, node);
      } else if (o.equals(value) && node.compareAndSet(value, null)) {
        unlink(prev, node);
        return true;
      } else {
        prev = node;
      }
      node = node.next;
    }
    return false;
  }

  /**
   * Unlinks the deleted node, given its predecessor node. This is called whenever a null value is
   * encountered during a traversal. This is necessary (although rare) because a previous removal
   * may have linked one node to another node that was also in the process of being removed. The
   * iterator's removal exploits the fact that nulls are cleaned out later to allow for lazy
   * deletion that would otherwise be O(n).
   *
   * @param previous the node before deleted, or null if deleted is first node
   * @param deleted the deleted node
   */
  void unlink(@Nullable Node<E> previous, Node<E> deleted) {
    if (previous == null) {
      casTop(deleted, deleted.next);
    } else {
      previous.next = deleted.next;
    }
    deleted.complete();
  }

  @Override
  public Iterator<E> iterator() {
    return new StackIterator();
  }

  /**
   * Returns a view as a last-in-first-out (LIFO) {@link Queue}. Method <tt>add</tt> is mapped to
   * <tt>push</tt>, <tt>remove</tt> is mapped to <tt>pop</tt> and so on. This view can be useful
   * when you would like to use a method requiring a <tt>Queue</tt> but you need LIFO ordering.
   *
   * @return the queue
   */
  public Queue<E> asLifoQueue() {
    return new AsLifoQueue<E>(this);
  }

  /** An iterator that traverses the stack, skipping elements that have been removed. */
  final class StackIterator implements Iterator<E> {
    Node<E> cursor;
    Node<E> next;
    E nextValue;

    StackIterator() {
      advance();
    }

    @Override
    public boolean hasNext() {
      return (next != null);
    }

    @Override
    public E next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return advance();
    }

    @Override
    public void remove() {
      if (cursor == null) {
        throw new IllegalStateException();
      }
      cursor.lazySet(null);
      cursor = null;
    }

    /**
     * Advances the cursor to the next valid node.
     *
     * @return the next value or {@code null} if there is none
     */
    @Nullable E advance() {
      E value = nextValue;
      cursor = next;

      Node<E> node = (cursor == null) ? top : next.next;
      for (;;) {
        if (node == null) {
          nextValue = null;
          next = null;
          return value;
        }
        nextValue = node.get();
        if (nextValue == null) {
          unlink(cursor, node);
          node = node.next;
        } else {
          next = node;
          return value;
        }
      }
    }
  }

  /** A view as a last-in-first-out (LIFO) {@link Queue}. */
  static final class AsLifoQueue<E> extends AbstractQueue<E> implements Queue<E>, Serializable {
    private static final long serialVersionUID = 1L;
    private final ConcurrentLinkedStack<E> stack;

    AsLifoQueue(ConcurrentLinkedStack<E> stack) {
      this.stack = stack;
    }

    @Override
    public boolean isEmpty() {
      return stack.isEmpty();
    }

    @Override
    public int size() {
      return stack.size();
    }

    @Override
    public void clear() {
      stack.clear();
    }

    @Override
    public boolean contains(@Nullable Object o) {
      return stack.contains(o);
    }

    @Override
    public E peek() {
      return stack.peek();
    }

    @Override
    public boolean offer(E e) {
      return stack.add(e);
    }

    @Override
    public E poll() {
      return stack.pop();
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
      return stack.addAll(c);
    }

    @Override
    public boolean remove(Object o) {
      return stack.remove(o);
    }

    @Override
    public Iterator<E> iterator() {
      return stack.iterator();
    }
  }

  /* ---------------- Serialization Support -------------- */

  static final long serialVersionUID = 1;

  Object writeReplace() {
    return new SerializationProxy<E>(this);
  }

  private void readObject(ObjectInputStream stream) throws InvalidObjectException {
    throw new InvalidObjectException("Proxy required");
  }

  /** A proxy that is serialized instead of the stack. */
  static final class SerializationProxy<E> implements Serializable {
    final boolean linearizable;
    final List<E> elements;

    SerializationProxy(ConcurrentLinkedStack<E> stack) {
      linearizable = (stack.factory.apply(null) instanceof LinearizableNode<?>);
      elements = new ArrayList<>(stack);
    }

    Object readResolve() {
      ConcurrentLinkedStack<E> stack = linearizable ? linearizable() : optimistic();
      Collections.reverse(elements);
      stack.addAll(elements);
      return stack;
    }

    static final long serialVersionUID = 1;
  }

  /**
   * An item on the stack. The node is mutable prior to being inserted to avoid object churn and
   * is immutable by the time it has been published to other threads.
   */
  static class Node<E> extends AtomicReference<E> {
    private static final long serialVersionUID = 1L;

    Node<E> next;

    Node(E value) {
      super(value);
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
      return getClass().getSimpleName() + "[" + get() + "]";
    }
  }

  static final class LinearizableNode<E> extends Node<E> {
    private static final long serialVersionUID = 1L;

    volatile boolean done;

    LinearizableNode(E value) {
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
final class CLSHeader {
  abstract static class PadTop<E> extends AbstractCollection<E> {
    long p00, p01, p02, p03, p04, p05, p06, p07;
    long p30, p31, p32, p33, p34, p35, p36, p37;
  }

  /** Enforces a memory layout to avoid false sharing by padding the top of the stack. */
  abstract static class TopRef<E> extends PadTop<E> {
    static final long TOP_OFFSET = UnsafeAccess.objectFieldOffset(TopRef.class, "top");

    volatile Node<E> top;

    boolean casTop(Node<E> expect, Node<E> update) {
      return UnsafeAccess.UNSAFE.compareAndSwapObject(this, TOP_OFFSET, expect, update);
    }
  }
}
