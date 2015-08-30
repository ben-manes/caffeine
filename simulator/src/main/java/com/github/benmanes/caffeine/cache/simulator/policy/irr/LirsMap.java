/*
 * Copyright 2010 Google Inc. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.policy.irr;

import java.util.AbstractMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A {@link java.util.Map} with a bounded size. If the cache is full when a new
 * entry is added, an old entry will first be evicted.
 *
 * <p>Eviction is accomplished using the <a
 * href="http://www.cse.ohio-state.edu/hpcs/WWW/HTML/publications/abs02-6.html">
 * LIRS</a> algorithm, which is superior to LRU as it combines frequency with
 * recency in selecting elements to evict. There is also a succinct summary of
 * LIRS in the <a href="http://lists.mysql.com/commits/28601">MySQL
 * documentation</a>.
 *
 * <p>While LRU is a common cache eviction algorithm, it has long been faulted
 * for its focus on recency at the expense of frequency. It also suffers from
 * not being scan-resistant. Numerous algorithms have been proposed to improve
 * upon LRU, inevitably by combining some notion frequency with recency when
 * selecting eviction candidates, such as LIRS, ARC, FBR, LRFU, LRU-2, 2Q, and
 * MQ. We have selected LIRS as its overhead is very similar to that of LRU,
 * while outperforming LRU and many other similar algorithms. It has also been
 * adopted by MySQL, NetBSD, and Linux (as reported in an <a
 * href="http://engineering.osu.edu/news/?p=544">OSU news article</a>).
 *
 * <p>Section 1.2 of the LIRS paper provides an executive summary of the policy:
 *
 * <blockquote>
 * "We use recent Inter-Reference Recency (IRR) as the recorded history
 * information of each block, where IRR of a block refers to the number of
 * other blocks accessed between two consecutive references to the block.
 * Specifically, the recency refers to the number of other blocks accessed
 * from last reference to the current time... We assume that if the IRR of a
 * block is large, the next IRR of the block is likely to be large again.
 * Following this assumption, we select the blocks with large IRRs for
 * replacement, because these blocks are highly possible to be evicted later
 * by LRU before being referenced again under our assumption. It is noted
 * that these evicted blocks may also have been recently accessed, i.e. each
 * has a small recency."
 * </blockquote>
 *
 * @author fry@google.com (Charles Fry)
 */
@SuppressWarnings("unused")
public class LirsMap<K, V> extends AbstractMap<K, V> {

  /**
   * The status of a cache entry.
   */
  private enum Status {
    HOT,    // resident LIRS, in stack, never in queue
    COLD,   // resident HIRS, always in queue, sometimes in stack
    NONRES  // non-resident HIRS, may be in stack, never in queue
  }

  /**
   * The percentage of the cache which is dedicated to hot blocks.
   * See section 5.1
   */
  private static final float HOT_RATE = 0.99f;

  /**
   * The backing map in which {@code LirsEntry}s are stored. It contains all
   * hot and cold entries, as well as all non-resident entries which are on
   * the stack.
   *
   * <p>Cache removals should not directly remove entries from this map, but
   * rather call {@link LirsEntry#remove()}, which will change the entry's
   * status to non-resident, while maintaining its recency.
   */
  private final ConcurrentMap<K, LirsEntry> backingMap;

  // LIRS fields

  /**
   * This header encompasses two data structures:
   *
   * <ul>
   * <li>The LIRS stack, S, which is maintains recency information. All hot
   * entries are on the stack. All cold and non-resident entries which are more
   * recent than the least recent hot entry are also stored in the stack (the
   * stack is always pruned such that the last entry is hot, and all entries
   * accessed more recently than the last hot entry are present in the stack).
   * The stack is ordered by recency, with its most recently accessed entry
   * at the top, and its least recently accessed entry at the bottom.</li>
   *
   * <li>The LIRS queue, Q, which enqueues all cold entries for eviction. Cold
   * entries (by definition in the queue) may be absent from the stack (due to
   * pruning of the stack). Cold entries are added to the end of the queue
   * and entries are evicted from the front of the queue.</li>
   * </ul>
   */
  private final LirsEntry header = new LirsEntry();

  /** The maximum number of hot entries (L_lirs in the paper). */
  private final int maximumHotSize;

  /** The maximum number of resident entries (L in the paper). */
  private final int maximumSize;

  /** The actual number of hot entries. */
  private int hotSize = 0;

  /** The actual number of resident entries. */
  private int size = 0;

  /**
   * Constructs a new {@code BoundedCache} with a maximum size of {@code
   * maximumSize}. The maximum size is maintained by evicting old entries
   * prior to the adding new entries when the cache is full.
   *
   * @param maximumSize the maximum number of entries to store in the map
   */
  public LirsMap(int maximumSize) {
    this.maximumSize = maximumSize;
    this.maximumHotSize = calculateMaxHotSize(maximumSize);
    this.backingMap = new ConcurrentHashMap<K, LirsEntry>(maximumSize);
  }

  /**
   * Returns the maximum hot size as a function of the maximum size. This is
   * defined to be the a percentage of the maximum size. When the maximum hot
   * size is equal to the maximum size the eviction algorithm reduces to LRU;
   * to avoid this we decrease the maximum hot size by one when it equals the
   * maximum size (and the maximum size is greater than one).
   */
  private static int calculateMaxHotSize(int maximumSize) {
    int result = (int) (HOT_RATE * maximumSize);
    return (result == maximumSize) ? maximumSize - 1 : result;
  }

  @Override
  public V get(Object key) {
    LirsEntry e = backingMap.get(key);
    if (e == null) {
      return null;
    }
    if (e.isResident()) {
      e.hit();
    } else {
      e.miss();
    }
    return e.getValue();
  }

  @Override
  public V put(K key, V value) {
    LirsEntry e = new LirsEntry(key, value);
    LirsEntry previous = backingMap.put(key, e);
    if (previous != null) {
      previous.remove();
      return previous.value;
    }
    return null;
  }

  @Override
  public V remove(Object key) {
    // don't remove from the map here, as that would discard its recency
    LirsEntry e = backingMap.get(key);
    return (e == null) ? null : e.remove();
  }

  /**
   * Returns the value associated with a key <i>without</i> accessing the key.
   * This allows test cases to observe the state of the cache without accessing
   * the cache (and changing the hot/cold status of entries).
   */
  V lookupElement(K key) {
    LirsEntry e = backingMap.get(key);
    if (e != null && e.isResident()) {
      return e.getValue();
    }
    return null;
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    throw new UnsupportedOperationException();
  }

  /**
   * Prunes HIR blocks in the bottom of the stack until an HOT block sits in
   * the stack bottom. If pruned blocks were resident, then they
   * remain in the queue; otherwise they are no longer referenced, and are thus
   * removed from the backing map.
   */
  private void pruneStack() {
    // See section 3.3:
    // "We define an operation called "stack pruning" on the LIRS
    // stack S, which removes the HIR blocks in the bottom of
    // the stack until an LIR block sits in the stack bottom. This
    // operation serves for two purposes: (1) We ensure the block in
    // the bottom of the stack always belongs to the LIR block set.
    // (2) After the LIR block in the bottom is removed, those HIR
    // blocks contiguously located above it will not have chances to
    // change their status from HIR to LIR, because their recencies
    // are larger than the new maximum recency of LIR blocks."
    LirsEntry bottom = stackBottom();
    while (bottom != null && bottom.status != Status.HOT) {
      bottom.removeFromStack();
      if (bottom.status == Status.NONRES) {
        // map only needs to hold nonresident entries that are on the stack
        backingMap.remove(bottom);
      }

      bottom = stackBottom();
    }
  }

  /**
   * Returns the entry at the top of the stack.
   */
  private LirsEntry stackTop() {
    LirsEntry top = header.nextInStack;
    return (top == header) ? null : top;
  }

  /**
   * Returns the entry at the bottom of the stack.
   */
  private LirsEntry stackBottom() {
    LirsEntry bottom = header.previousInStack;
    return (bottom == header) ? null : bottom;
  }

  /**
   * Returns the entry at the front of the queue.
   */
  private LirsEntry queueFront() {
    LirsEntry front = header.nextInQueue;
    return (front == header) ? null : front;
  }

  /**
   * Returns the entry at the end of the queue.
   */
  private LirsEntry queueEnd() {
    LirsEntry end = header.previousInQueue;
    return (end == header) ? null : end;
  }

  /**
   * Returns a string representation of the stack. Useful for debugging.
   */
  public String printStack() {
    StringBuilder result = new StringBuilder();
    result.append("[");
    LirsEntry e = stackTop();
    if (e != null) {
      result.append(e);
      for (e = e.nextInStack; e != header; e = e.nextInStack) {
        result.append(", " + e);
      }
    }
    result.append("]");
    return result.toString();
  }

  /**
   * Returns a string representation of the stack. Useful for debugging.
   */
  public String printQueue() {
    StringBuilder result = new StringBuilder();
    result.append("[");
    LirsEntry e = queueFront();
    if (e != null) {
      result.append(e);
      for (e = e.nextInQueue; e != header; e = e.nextInQueue) {
        result.append(", " + e);
      }
    }
    result.append("]");
    return result.toString();
  }

  /**
   * Wraps a key with pointers into the LIRS stack and queue.
   */
  private class LirsEntry {
    // The underlying entry key and value.
    private final K key;
    private V value;

    /**
     * The status of this entry (hot/cold/non-resident). This should never be
     * changed manually, but rather using the methods {@link #hot()},
     * {@link #cold()}, and {@link #nonResident()} in order to ensure that
     * proper book-keeping is done of the cache size and the hot entry count.
     */
    private Status status = Status.NONRES;

    // LIRS stack S
    private LirsEntry previousInStack;
    private LirsEntry nextInStack;

    // LIRS queue Q
    private LirsEntry previousInQueue;
    private LirsEntry nextInQueue;

    // Note that the queue could be implemented as a separate data structure.
    // This would remove these two references from each entry, but it would
    // require the queue to be traversed when looking for elements in the queue.

    /**
     * Constructs a new entry for a given key and value, adding it to the
     * appropriate LIRS data structures.
     */
    public LirsEntry(K key, V value) {
      this.key = key;
      this.value = value;

      miss();
    }

    /**
     * Creates a header entry.
     */
    public LirsEntry() {
      this.key = null;
      this.value = null;

      // initially point everything back to self
      this.previousInStack = this;
      this.nextInStack = this;
      this.previousInQueue = this;
      this.nextInQueue = this;
    }

    /**
     * Returns this entry's value.
     */
    public V getValue() {
      return value;
    }

    public void setValue(V value) {
      this.value = value;
    }

    /**
     * Returns true if this entry is resident in the cache, false otherwise.
     */
    public boolean isResident() {
      return (status != Status.NONRES);
    }

    /**
     * Returns true if this entry is in the stack, false otherwise.
     */
    public boolean inStack() {
      return (nextInStack != null);
    }

    /**
     * Returns true if this entry is in the queue, false otherwise.
     */
    public boolean inQueue() {
      return (nextInQueue != null);
    }

    /**
     * Records a cache hit.
     */
    public void hit() {
      switch (status) {
        case HOT:
          hotHit();
          break;
        case COLD:
          coldHit();
          break;
        case NONRES:
          throw new IllegalStateException("Can't hit a non-resident entry!");
        default:
          throw new AssertionError("Hit with unknown status: " + status);
      }
    }

    /**
     * Records a cache hit on a hot block.
     */
    private void hotHit() {
      // See section 3.3 case 1:
      // "Upon accessing an LIR block X:
      // This access is guaranteed to be a hit in the cache."

      // "We move it to the top of stack S."
      boolean onBottom = (stackBottom() == this);
      moveToStackTop();

      // "If the LIR block is originally located in the bottom of the stack,
      // we conduct a stack pruning."
      if (onBottom) {
        pruneStack();
      }
    }

    /**
     * Records a cache hit on a cold block.
     */
    private void coldHit() {
      // See section 3.3 case 2:
      // "Upon accessing an HIR resident block X:
      // This is a hit in the cache."

      // "We move it to the top of stack S."
      boolean inStack = inStack();
      moveToStackTop();

      // "There are two cases for block X:"
      if (inStack) {
        // "(1) If X is in the stack S, we change its status to LIR."
        hot();

        // "This block is also removed from list Q."
        removeFromQueue();

        // "The LIR block in the bottom of S is moved to the end of list Q
        // with its status changed to HIR."
        stackBottom().migrateToQueue();

        // "A stack pruning is then conducted."
        pruneStack();
      } else {
        // "(2) If X is not in stack S, we leave its status in HIR and move
        // it to the end of list Q."
        moveToQueueEnd();
      }
    }

    /**
     * Records a cache miss. This is how new entries join the LIRS stack and
     * queue. This is called both when a new entry is first created, and when a
     * non-resident entry is re-computed.
     */
    private void miss() {
      if (hotSize < maximumHotSize) {
        warmupMiss();
      } else {
        fullMiss();
      }

      // now the missed item is in the cache
      size++;
    }

    /**
     * Records a miss when the hot entry set is not full.
     */
    private void warmupMiss() {
      // See section 3.3:
      // "When LIR block set is not full, all the referenced blocks are
      // given an LIR status until its size reaches L_lirs."
      hot();
      moveToStackTop();
    }

    /**
     * Records a miss when the hot entry set is full.
     */
    private void fullMiss() {
      // See section 3.3 case 3:
      // "Upon accessing an HIR non-resident block X:
      // This is a miss."

      // This condition is unspecified in the paper, but appears to be
      // necessary.
      if (size >= maximumSize) {
        // "We remove the HIR resident block at the front of list Q (it then
        // becomes a non-resident block), and replace it out of the cache."
        queueFront().evict();
      }

      // "Then we load the requested block X into the freed buffer and place
      // it on the top of stack S."
      boolean inStack = inStack();
      moveToStackTop();

      // "There are two cases for block X:"
      if (inStack) {
        // "(1) If X is in stack S, we change its status to LIR and move the
        // LIR block in the bottom of stack S to the end of list Q with its
        // status changed to HIR. A stack pruning is then conducted.
        hot();
        stackBottom().migrateToQueue();
        pruneStack();
      } else {
        // "(2) If X is not in stack S, we leave its status in HIR and place
        // it in the end of list Q."
        cold();
      }
    }

    /**
     * Marks this entry as hot.
     */
    private void hot() {
      if (status != Status.HOT) {
        hotSize++;
      }
      status = Status.HOT;
    }

    /**
     * Marks this entry as cold.
     */
    private void cold() {
      if (status == Status.HOT) {
        hotSize--;
      }
      status = Status.COLD;
      moveToQueueEnd();
    }

    /**
     * Marks this entry as non-resident.
     */
    @SuppressWarnings("fallthrough")
    private void nonResident() {
      switch (status) {
        case HOT:
          hotSize--;
          // fallthrough
        case COLD:
          size--;
        default:
      }
      status = Status.NONRES;
    }

    /**
     * Temporarily removes this entry from the stack, fixing up neighbor links.
     * This entry's links remain unchanged, meaning that {@link #inStack()} will
     * continue to return true. This should only be called if this node's links
     * will be subsequently changed.
     */
    private void tempRemoveFromStack() {
      if (inStack()) {
        previousInStack.nextInStack = nextInStack;
        nextInStack.previousInStack = previousInStack;
      }
    }

    /**
     * Removes this entry from the stack.
     */
    private void removeFromStack() {
      tempRemoveFromStack();
      previousInStack = null;
      nextInStack = null;
    }

    /**
     * Inserts this entry before the specified existing entry in the stack.
     */
    private void addToStackBefore(LirsEntry existingEntry) {
      previousInStack = existingEntry.previousInStack;
      nextInStack = existingEntry;
      previousInStack.nextInStack = this;
      nextInStack.previousInStack = this;
    }

    /**
     * Moves this entry to the top of the stack.
     */
    private void moveToStackTop() {
      tempRemoveFromStack();
      addToStackBefore(header.nextInStack);
    }

    /**
     * Moves this entry to the bottom of the stack.
     */
    private void moveToStackBottom() {
      tempRemoveFromStack();
      addToStackBefore(header);
    }

    /**
     * Temporarily removes this entry from the queue, fixing up neighbor links.
     * This entry's links remain unchanged. This should only be called if this
     * node's links will be subsequently changed.
     */
    private void tempRemoveFromQueue() {
      if (inQueue()) {
        previousInQueue.nextInQueue = nextInQueue;
        nextInQueue.previousInQueue = previousInQueue;
      }
    }

    /**
     * Removes this entry from the queue.
     */
    private void removeFromQueue() {
      tempRemoveFromQueue();
      previousInQueue = null;
      nextInQueue = null;
    }

    /**
     * Inserts this entry before the specified existing entry in the queue.
     */
    private void addToQueueBefore(LirsEntry existingEntry) {
      previousInQueue = existingEntry.previousInQueue;
      nextInQueue = existingEntry;
      previousInQueue.nextInQueue = this;
      nextInQueue.previousInQueue = this;
    }

    /**
     * Moves this entry to the end of the queue.
     */
    private void moveToQueueEnd() {
      tempRemoveFromQueue();
      addToQueueBefore(header);
    }

    /**
     * Moves this entry from the stack to the queue, marking it cold
     * (as hot entries must remain in the stack). This should only be called
     * on resident entries, as non-resident entries should not be made resident.
     * The bottom entry on the queue is always hot due to stack pruning.
     */
    private void migrateToQueue() {
      removeFromStack();
      cold();
    }

    /**
     * Moves this entry from the queue to the stack, marking it hot (as cold
     * resident entries must remain in the queue).
     */
    private void migrateToStack() {
      removeFromQueue();
      if (!inStack()) {
        moveToStackBottom();
      }
      hot();
    }

    /**
     * Evicts this entry, removing it from the queue and setting its status to
     * cold non-resident. If the entry is already absent from the stack, it is
     * removed from the backing map; otherwise it remains in order for its
     * recency to be maintained.
     */
    private void evict() {
      removeFromQueue();
      // without computation nonresident entries are never revived
      removeFromStack();
      backingMap.remove(key, this);
      nonResident();

      value = null;
    }

    /**
     * Removes this entry from the cache. This operation is not specified in
     * the paper, which does not account for forced eviction.
     */
    private V remove() {
      boolean wasHot = (status == Status.HOT);
      V result = value;
      evict();

      // attempt to maintain a constant number of hot entries
      if (wasHot) {
        LirsEntry end = queueEnd();
        if (end != null) {
          end.migrateToStack();
        }
      }

      return result;
    }

    @Override
    public String toString() {
      return key + "=" + value + " [" + status + "]";
    }
  }
}
