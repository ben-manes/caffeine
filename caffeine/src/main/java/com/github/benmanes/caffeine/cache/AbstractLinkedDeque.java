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
package com.github.benmanes.caffeine.cache;

import static java.util.Objects.requireNonNull;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.NoSuchElementException;

import org.jspecify.annotations.Nullable;

import com.google.errorprone.annotations.Var;

/**
 * This class provides a skeletal implementation of the {@link LinkedDeque} interface to minimize
 * the effort required to implement this interface.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @param <E> the type of elements held in this collection
 */
abstract class AbstractLinkedDeque<E> extends AbstractCollection<E> implements LinkedDeque<E> {

  // This class provides a doubly-linked list that is optimized for the virtual machine. The first
  // and last elements are manipulated instead of a slightly more convenient sentinel element to
  // avoid the insertion of null checks with NullPointerException throws in the byte code. The links
  // to a removed element are cleared to help a generational garbage collector if the discarded
  // elements inhabit more than one generation.

  /**
   * Pointer to first node.
   * Invariant: (first == null && last == null) ||
   *            (first.prev == null)
   */
  @Nullable E first;

  /**
   * Pointer to last node.
   * Invariant: (first == null && last == null) ||
   *            (last.next == null)
   */
  @Nullable E last;

  /**
   * The number of times this deque has been <i>structurally modified</i>. Structural modifications
   * are those that change the size of the deque, or otherwise perturb it in such a fashion that
   * iterations in progress may yield incorrect results.
   */
  int modCount;

  /**
   * Links the element to the front of the deque so that it becomes the first element.
   *
   * @param e the unlinked element
   */
  void linkFirst(E e) {
    E f = first;
    first = e;

    if (f == null) {
      last = e;
    } else {
      setPrevious(f, e);
      setNext(e, f);
    }
    modCount++;
  }

  /**
   * Links the element to the back of the deque so that it becomes the last element.
   *
   * @param e the unlinked element
   */
  void linkLast(E e) {
    E l = last;
    last = e;

    if (l == null) {
      first = e;
    } else {
      setNext(l, e);
      setPrevious(e, l);
    }
    modCount++;
  }

  /** Unlinks the non-null first element. */
  @SuppressWarnings("NullAway")
  E unlinkFirst() {
    E f = first;
    E next = getNext(f);
    setNext(f, null);

    first = next;
    if (next == null) {
      last = null;
    } else {
      setPrevious(next, null);
    }
    modCount++;
    return f;
  }

  /** Unlinks the non-null last element. */
  @SuppressWarnings("NullAway")
  E unlinkLast() {
    E l = last;
    E prev = getPrevious(l);
    setPrevious(l, null);
    last = prev;
    if (prev == null) {
      first = null;
    } else {
      setNext(prev, null);
    }
    modCount++;
    return l;
  }

  /** Unlinks the non-null element. */
  void unlink(E e) {
    E prev = getPrevious(e);
    E next = getNext(e);

    if (prev == null) {
      first = next;
    } else {
      setNext(prev, next);
      setPrevious(e, null);
    }

    if (next == null) {
      last = prev;
    } else {
      setPrevious(next, prev);
      setNext(e, null);
    }
    modCount++;
  }

  @Override
  public boolean isEmpty() {
    return (first == null);
  }

  void checkNotEmpty() {
    if (isEmpty()) {
      throw new NoSuchElementException();
    }
  }

  /**
   * {@inheritDoc}
   * <p>
   * Beware that, unlike in most collections, this method is <em>NOT</em> a constant-time operation.
   */
  @Override
  public int size() {
    @Var int size = 0;
    for (E e = first; e != null; e = getNext(e)) {
      size++;
    }
    return size;
  }

  @Override
  public void clear() {
    @Var E e = first;
    while (e != null) {
      E next = getNext(e);
      setPrevious(e, null);
      setNext(e, null);
      e = next;
    }
    first = last = null;
    modCount++;
  }

  @Override
  public abstract boolean contains(Object o);

  @Override
  public boolean isFirst(@Nullable E e) {
    return (e != null) && (e == first);
  }

  @Override
  public boolean isLast(@Nullable E e) {
    return (e != null) && (e == last);
  }

  @Override
  public void moveToFront(E e) {
    if (e != first) {
      unlink(e);
      linkFirst(e);
    }
  }

  @Override
  public void moveToBack(E e) {
    if (e != last) {
      unlink(e);
      linkLast(e);
    }
  }

  @Override
  public @Nullable E peek() {
    return peekFirst();
  }

  @Override
  public @Nullable E peekFirst() {
    return first;
  }

  @Override
  public @Nullable E peekLast() {
    return last;
  }

  @Override
  @SuppressWarnings("NullAway")
  public E getFirst() {
    checkNotEmpty();
    return peekFirst();
  }

  @Override
  @SuppressWarnings("NullAway")
  public E getLast() {
    checkNotEmpty();
    return peekLast();
  }

  @Override
  public E element() {
    return getFirst();
  }

  @Override
  public boolean offer(E e) {
    return offerLast(e);
  }

  @Override
  public boolean offerFirst(E e) {
    requireNonNull(e);
    if (contains(e)) {
      return false;
    }
    linkFirst(e);
    return true;
  }

  @Override
  public boolean offerLast(E e) {
    requireNonNull(e);
    if (contains(e)) {
      return false;
    }
    linkLast(e);
    return true;
  }

  @Override
  public boolean add(E e) {
    return offerLast(e);
  }

  @Override
  public void addFirst(E e) {
    if (!offerFirst(e)) {
      throw new IllegalArgumentException();
    }
  }

  @Override
  public void addLast(E e) {
    if (!offerLast(e)) {
      throw new IllegalArgumentException();
    }
  }

  @Override
  public @Nullable E poll() {
    return pollFirst();
  }

  @Override
  public @Nullable E pollFirst() {
    return isEmpty() ? null : unlinkFirst();
  }

  @Override
  public @Nullable E pollLast() {
    return isEmpty() ? null : unlinkLast();
  }

  @Override
  public E remove() {
    return removeFirst();
  }

  @Override
  @SuppressWarnings("NullAway")
  public E removeFirst() {
    checkNotEmpty();
    return pollFirst();
  }

  @Override
  public abstract boolean remove(Object o);

  @Override
  public boolean removeFirstOccurrence(Object o) {
    return remove(o);
  }

  @Override
  @SuppressWarnings("NullAway")
  public E removeLast() {
    checkNotEmpty();
    return pollLast();
  }

  @Override
  public boolean removeLastOccurrence(Object o) {
    return remove(o);
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    @Var boolean modified = false;
    for (Object o : c) {
      modified |= remove(o);
    }
    return modified;
  }

  @Override
  public void push(E e) {
    addFirst(e);
  }

  @Override
  public E pop() {
    return removeFirst();
  }

  @Override
  public PeekingIterator<E> iterator() {
    return new AbstractLinkedIterator(first) {
      @SuppressWarnings("NullAway")
      @Override @Nullable E computeNext() {
        return getNext(cursor);
      }
    };
  }

  @Override
  public PeekingIterator<E> descendingIterator() {
    return new AbstractLinkedIterator(last) {
      @SuppressWarnings("NullAway")
      @Override @Nullable E computeNext() {
        return getPrevious(cursor);
      }
    };
  }

  abstract class AbstractLinkedIterator implements PeekingIterator<E> {
    @Nullable E previous;
    @Nullable E cursor;

    int expectedModCount;

    /**
     * Creates an iterator that can can traverse the deque.
     *
     * @param start the initial element to begin traversal from
     */
    AbstractLinkedIterator(@Nullable E start) {
      expectedModCount = modCount;
      cursor = start;
    }

    @Override
    public boolean hasNext() {
      checkForComodification();
      return (cursor != null);
    }

    @Override
    public @Nullable E peek() {
      return cursor;
    }

    @Override
    @SuppressWarnings("NullAway")
    public E next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      previous = cursor;
      cursor = computeNext();
      return previous;
    }

    /** Retrieves the next element to traverse to or {@code null} if there are no more elements. */
    abstract @Nullable E computeNext();

    @Override
    public void remove() {
      if (previous == null) {
        throw new IllegalStateException();
      }
      checkForComodification();

      AbstractLinkedDeque.this.remove(previous);
      expectedModCount = modCount;
      previous = null;
    }

    /**
     * If the expected modCount value that the iterator believes that the backing deque should have
     * is violated then the iterator has detected concurrent modification.
     */
    void checkForComodification() {
      if (modCount != expectedModCount) {
        throw new ConcurrentModificationException();
      }
    }
  }
}
