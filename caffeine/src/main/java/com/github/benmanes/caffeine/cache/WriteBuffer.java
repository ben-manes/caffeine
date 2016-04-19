/*
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

import static com.github.benmanes.caffeine.cache.BoundedLocalCache.ceilingNextPowerOfTwo;
import static java.util.Objects.requireNonNull;

import com.github.benmanes.caffeine.base.UnsafeAccess;
import com.github.benmanes.caffeine.cache.WBHeader.ConsumerRef;

/**
 * An MPSC array queue which starts at <i>initialCapacity</i> and grows to <i>maxCapacity</i> in
 * linked chunks of the initial size. The queue grows only when the current buffer is full and
 * elements are not copied on resize, instead a link to the new buffer is stored in the old buffer
 * for the consumer to follow.
 * <p>
 * This is a trimmed down version of <tt>MpscChunkedArrayQueue</tt> provided by
 * <a href="https://github.com/JCTools/JCTools">JCTools</a>.
 *
 * @author nitsanw@yahoo.com (Nitsan Wakart)
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("restriction")
final class WriteBuffer<E> extends ConsumerRef<E> {
  long p60, p61, p62, p63, p64, p65, p66, p67;
  long p70, p71, p72, p73, p74, p75, p76;

  static final long ARRAY_BASE = UnsafeAccess.UNSAFE.arrayBaseOffset(Object[].class);
  static final Object JUMP = new Object();
  static final int ELEMENT_SHIFT;

  /**
   * @param initialCapacity the queue initial capacity. If chunk size is fixed this will be the
   *        chunk size. Must be 2 or more.
   * @param maxCapacity the maximum capacity will be rounded up to the closest power of 2 and will
   *        be the upper limit of number of elements in this queue. Must be 4 or more and round up
   *        to a larger power of 2 than initialCapacity.
   */
  public WriteBuffer(int initialCapacity, int maxCapacity) {
    Caffeine.requireArgument(maxCapacity > 4);
    Caffeine.requireArgument(initialCapacity > 2);
    if (ceilingNextPowerOfTwo(initialCapacity) >= ceilingNextPowerOfTwo(maxCapacity)) {
      throw new IllegalArgumentException(
          "Initial capacity cannot exceed maximum capacity(both rounded up to a power of 2)");
    }

    int p2capacity = ceilingNextPowerOfTwo(initialCapacity);
    // leave lower bit of mask clear
    long mask = (p2capacity - 1) << 1;
    // need extra element to point at next array
    @SuppressWarnings("unchecked")
    E[] buffer = (E[]) new Object[p2capacity + 1];
    producerBuffer = buffer;
    producerMask = mask;
    consumerBuffer = buffer;
    consumerMask = mask;
    maxQueueCapacity = ceilingNextPowerOfTwo(maxCapacity) << 1;
    soProducerLimit(mask); // we know it's all empty to start with
  }

  @SuppressWarnings({"PMD.SwitchStmtsShouldHaveDefault", "PMD.MissingBreakInSwitch"})
  public boolean offer(E e) {
    requireNonNull(e);

    long mask;
    E[] buffer;
    long producerIndex;
    for (;;) {
      long producerLimit = lvProducerLimit();
      producerIndex = lvProducerIndex();

      // lower bit is indicative of resize, if we see it we spin until it's cleared
      if ((producerIndex & 1) == 1) {
        continue;
      }
      // producerIndex is even (lower bit is 0) -> actual index is (producerIndex >> 1)

      // mask/buffer may get changed by resizing -> only use for array access after successful CAS
      mask = producerMask;
      buffer = producerBuffer;
      // a successful CAS ties the ordering, lv(producerIndex)-[mask/buffer]->cas(producerIndex)

      // assumption behind this optimization is that queue is almost always empty or near empty
      if (producerLimit <= producerIndex) {
        int result = offerSlowPath(e, mask, buffer, producerIndex, producerLimit);
        switch (result) {
          case 0: break;
          case 1: continue;
          case 2: return false;
          case 3: return true;
        }
      }

      if (casProducerIndex(producerIndex, producerIndex + 2)) {
        break;
      }
    }

    long offset = modifiedCalcElementOffset(producerIndex, mask);
    UnsafeAccess.UNSAFE.putOrderedObject(buffer, offset, e);
    return true;
  }

  private int offerSlowPath(E e, long mask, E[] buffer, long producerIndex, long producerLimit) {
    int result;
    long consumerIndex = lvConsumerIndex();
    long maxQueueCapacity = this.maxQueueCapacity;
    long bufferCapacity = ((mask + 2) == maxQueueCapacity) ? maxQueueCapacity : mask;

    if (consumerIndex + bufferCapacity > producerIndex) {
      if (!casProducerLimit(producerLimit, consumerIndex + bufferCapacity)) {
        result = 1;// retry from top
      }
      result = 0;// 0 - goto producerIndex CAS
    }
    // full and cannot grow
    else if (consumerIndex == (producerIndex - maxQueueCapacity)) {
      result = 2;// -> return false;
    }
    // grab index for resize -> set lower bit
    else if (casProducerIndex(producerIndex, producerIndex + 1)) {
      // resize will adjust the consumerIndexCache
      int newBufferLength = buffer.length;
      if (buffer.length - 1 == maxQueueCapacity) {
        throw new IllegalStateException();
      }
      newBufferLength = 2 * buffer.length - 1;

      @SuppressWarnings("unchecked")
      E[] newBuffer = (E[]) new Object[newBufferLength];

      producerBuffer = newBuffer;
      producerMask = (newBufferLength - 2) << 1;

      long offsetInOld = modifiedCalcElementOffset(producerIndex, mask);
      long offsetInNew = modifiedCalcElementOffset(producerIndex, producerMask);
      UnsafeAccess.UNSAFE.putOrderedObject(newBuffer, offsetInNew, e);
      UnsafeAccess.UNSAFE.putOrderedObject(buffer, nextArrayOffset(mask), newBuffer);
      long available = maxQueueCapacity - (producerIndex - consumerIndex);

      if (available <= 0) {
        throw new IllegalStateException();
      }
      // invalidate racing CASs
      soProducerLimit(producerIndex + Math.min(mask, available));

      // make resize visible to consumer
      UnsafeAccess.UNSAFE.putOrderedObject(buffer, offsetInOld, JUMP);

      // make resize visible to the other producers
      soProducerIndex(producerIndex + 2);
      result = 3;// -> return true
    } else {
      result = 1;// failed resize attempt, retry from top
    }
    return result;
  }

  @SuppressWarnings("PMD.ConfusingTernary")
  public E poll() {
    E[] buffer = consumerBuffer;
    long index = consumerIndex;
    long mask = consumerMask;

    long offset = modifiedCalcElementOffset(index, mask);
    Object e = lvElement(buffer, offset);// LoadLoad
    if (e == null) {
      if (index != lvProducerIndex()) {
        // poll() == null iff queue is empty, null element is not strong enough indicator, so we
        // must check the producer index. If the queue is indeed not empty we spin until element is
        // visible.
        do {
          e = lvElement(buffer, offset);
        } while (e == null);
      } else {
        return null;
      }
    }
    if (e == JUMP) {
      final E[] nextBuffer = getNextBuffer(buffer, mask);
      return newBufferPoll(nextBuffer, index);
    }
    soElement(buffer, offset, null);
    soConsumerIndex(index + 2);

    @SuppressWarnings("unchecked")
    E casted = (E) e;
    return casted;
  }

  public int size() {
    /*
     * It is possible for a thread to be interrupted or reschedule between the read of the producer
     * and consumer indices, therefore protection is required to ensure size is within valid range.
     * In the event of concurrent polls/offers to this method the size is OVER estimated as we read
     * consumer index BEFORE the producer index.
     */
    long after = lvConsumerIndex();
    for (;;) {
      long before = after;
      long currentProducerIndex = lvProducerIndex();
      after = lvConsumerIndex();
      if (before == after) {
        return (int) (currentProducerIndex - after) >> 1;
      }
    }
  }

  /**
   * This method assumes index is actually (index << 1) because lower bit is used for resize. This
   * is compensated for by reducing the element shift. The computation is constant folded, so
   * there's no cost.
   */
  private static long modifiedCalcElementOffset(long index, long mask) {
    return ARRAY_BASE + ((index & mask) << (ELEMENT_SHIFT - 1));
  }

  @SuppressWarnings("unchecked")
  private E[] getNextBuffer(E[] buffer, long mask) {
    long nextArrayOffset = nextArrayOffset(mask);
    E[] nextBuffer = (E[]) UnsafeAccess.UNSAFE.getObjectVolatile(buffer, nextArrayOffset);
    UnsafeAccess.UNSAFE.putOrderedObject(buffer, nextArrayOffset, null);
    return nextBuffer;
  }

  private long nextArrayOffset(long mask) {
    return modifiedCalcElementOffset(mask + 2, Long.MAX_VALUE);
  }

  @SuppressWarnings("unchecked")
  private E newBufferPoll(E[] nextBuffer, long index) {
    long offsetInNew = newBufferAndOffset(nextBuffer, index);
    E n = (E) UnsafeAccess.UNSAFE.getObjectVolatile(nextBuffer, offsetInNew);// LoadLoad
    if (n == null) {
      throw new IllegalStateException("new buffer must have at least one element");
    }
    UnsafeAccess.UNSAFE.putOrderedObject(nextBuffer, offsetInNew, null);// StoreStore
    soConsumerIndex(index + 2);
    return n;
  }

  @SuppressWarnings("PMD.ArrayIsStoredDirectly")
  private long newBufferAndOffset(E[] nextBuffer, long index) {
    consumerBuffer = nextBuffer;
    consumerMask = (nextBuffer.length - 2) << 1;
    long offsetInNew = modifiedCalcElementOffset(index, consumerMask);
    return offsetInNew;
  }

  @SuppressWarnings("unchecked")
  public static <E> E lvElement(E[] buffer, long offset) {
    return (E) UnsafeAccess.UNSAFE.getObjectVolatile(buffer, offset);
  }

  public static <E> void soElement(E[] buffer, long offset, E e) {
    UnsafeAccess.UNSAFE.putOrderedObject(buffer, offset, e);
  }

  private long lvProducerIndex() {
    return UnsafeAccess.UNSAFE.getLongVolatile(this, P_INDEX_OFFSET);
  }

  private long lvConsumerIndex() {
    return UnsafeAccess.UNSAFE.getLongVolatile(this, C_INDEX_OFFSET);
  }

  private void soProducerIndex(long v) {
    UnsafeAccess.UNSAFE.putOrderedLong(this, P_INDEX_OFFSET, v);
  }

  private boolean casProducerIndex(long expect, long newValue) {
    return UnsafeAccess.UNSAFE.compareAndSwapLong(this, P_INDEX_OFFSET, expect, newValue);
  }

  private void soConsumerIndex(long v) {
    UnsafeAccess.UNSAFE.putOrderedLong(this, C_INDEX_OFFSET, v);
  }

  private long lvProducerLimit() {
    return producerLimit;
  }

  private boolean casProducerLimit(long expect, long newValue) {
    return UnsafeAccess.UNSAFE.compareAndSwapLong(this, P_LIMIT_OFFSET, expect, newValue);
  }

  private void soProducerLimit(long v) {
    UnsafeAccess.UNSAFE.putOrderedLong(this, P_LIMIT_OFFSET, v);
  }

  static {
    int scale = UnsafeAccess.UNSAFE.arrayIndexScale(Object[].class);
    if (scale == 4) {
      ELEMENT_SHIFT = 2;
    } else if (scale == 8) {
      ELEMENT_SHIFT = 3;
    } else {
      throw new IllegalStateException("Unknown pointer size");
    }
  }
}

/** The namespace for field padding through inheritance. */
final class WBHeader {
  @SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
  static abstract class PadProducerIndex<E> {
    long p00, p01, p02, p03, p04, p05, p06, p07;
    long p10, p11, p12, p13, p14, p15, p16;
  }

  static abstract class ProducerIndexRef<E> extends PadProducerIndex<E> {
    static final long P_INDEX_OFFSET =
        UnsafeAccess.objectFieldOffset(ProducerIndexRef.class, "producerIndex");

    long producerIndex;
  }

  static abstract class PadColdProducer<E> extends ProducerIndexRef<E> {
    long p20, p21, p22, p23, p24, p25, p26, p27;
    long p30, p31, p32, p33, p34, p35, p36;
  }

  static abstract class ColdProducerRef<E> extends PadColdProducer<E> {
    static final long P_LIMIT_OFFSET =
        UnsafeAccess.objectFieldOffset(ColdProducerRef.class, "producerLimit");

    long maxQueueCapacity;
    long producerMask;
    E[] producerBuffer;
    volatile long producerLimit;
  }

  static abstract class PadConsumer<E> extends ColdProducerRef<E> {
    long p40, p41, p42, p43, p44, p45, p46, p47;
    long p50, p51, p52, p53, p54, p55, p56;
  }

  static abstract class ConsumerRef<E> extends PadConsumer<E> {
    static final long C_INDEX_OFFSET =
        UnsafeAccess.objectFieldOffset(ConsumerRef.class, "consumerIndex");

    long consumerMask;
    E[] consumerBuffer;
    long consumerIndex;
  }
}
