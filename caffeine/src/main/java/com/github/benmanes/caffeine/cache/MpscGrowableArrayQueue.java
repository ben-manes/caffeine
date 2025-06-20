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

import static com.github.benmanes.caffeine.cache.Caffeine.ceilingPowerOfTwo;
import static com.github.benmanes.caffeine.cache.Caffeine.requireArgument;
import static com.github.benmanes.caffeine.cache.Caffeine.requireState;
import static java.util.Objects.requireNonNull;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.util.AbstractQueue;
import java.util.Iterator;

import org.jspecify.annotations.Nullable;

import com.google.errorprone.annotations.Var;

/**
 * An MPSC array queue which starts at <i>initialCapacity</i> and grows to <i>maxCapacity</i> in
 * linked chunks of the initial size. The queue grows only when the current buffer is full and
 * elements are not copied on resize, instead a link to the new buffer is stored in the old buffer
 * for the consumer to follow.<br>
 * <p>
 * This is a shaded copy of <code>MpscGrowableArrayQueue</code> provided by
 * <a href="https://github.com/JCTools/JCTools">JCTools</a> from version 2.0.
 *
 * @author nitsanw@yahoo.com (Nitsan Wakart)
 */
class MpscGrowableArrayQueue<E> extends MpscChunkedArrayQueue<E> {

  /**
   * @param initialCapacity the queue initial capacity. If chunk size is fixed this will be the
   *        chunk size. Must be 2 or more.
   * @param maxCapacity the maximum capacity will be rounded up to the closest power of 2 and will
   *        be the upper limit of number of elements in this queue. Must be 4 or more and round up
   *        to a larger power of 2 than initialCapacity.
   */
  MpscGrowableArrayQueue(int initialCapacity, int maxCapacity) {
    super(initialCapacity, maxCapacity);
  }

  @Override
  protected int getNextBufferSize(@Nullable E[] buffer) {
    long maxSize = maxQueueCapacity / 2;
    requireState(maxSize >= buffer.length);
    int newSize = 2 * (buffer.length - 1);
    return newSize + 1;
  }

  @Override
  protected long getCurrentBufferCapacity(long mask) {
    return (mask + 2 == maxQueueCapacity) ? maxQueueCapacity : mask;
  }
}

@SuppressWarnings({"MultiVariableDeclaration",
    "OvershadowingSubclassFields", "PMD.OneDeclarationPerLine"})
abstract class MpscChunkedArrayQueue<E> extends MpscChunkedArrayQueueColdProducerFields<E> {
  byte p000, p001, p002, p003, p004, p005, p006, p007;
  byte p008, p009, p010, p011, p012, p013, p014, p015;
  byte p016, p017, p018, p019, p020, p021, p022, p023;
  byte p024, p025, p026, p027, p028, p029, p030, p031;
  byte p032, p033, p034, p035, p036, p037, p038, p039;
  byte p040, p041, p042, p043, p044, p045, p046, p047;
  byte p048, p049, p050, p051, p052, p053, p054, p055;
  byte p056, p057, p058, p059, p060, p061, p062, p063;
  byte p064, p065, p066, p067, p068, p069, p070, p071;
  byte p072, p073, p074, p075, p076, p077, p078, p079;
  byte p080, p081, p082, p083, p084, p085, p086, p087;
  byte p088, p089, p090, p091, p092, p093, p094, p095;
  byte p096, p097, p098, p099, p100, p101, p102, p103;
  byte p104, p105, p106, p107, p108, p109, p110, p111;
  byte p112, p113, p114, p115, p116, p117, p118, p119;

  MpscChunkedArrayQueue(int initialCapacity, int maxCapacity) {
    super(initialCapacity, maxCapacity);
  }

  @Override
  protected long availableInQueue(long pIndex, long cIndex) {
    return maxQueueCapacity - (pIndex - cIndex);
  }

  @Override
  public int capacity() {
    return (int) (maxQueueCapacity / 2);
  }
}

abstract class MpscChunkedArrayQueueColdProducerFields<E> extends BaseMpscLinkedArrayQueue<E> {
  protected final long maxQueueCapacity;

  MpscChunkedArrayQueueColdProducerFields(int initialCapacity, int maxCapacity) {
    super(initialCapacity);
    requireArgument(maxCapacity >= 4, "Max capacity must be 4 or more");
    requireArgument(ceilingPowerOfTwo(maxCapacity) >= ceilingPowerOfTwo(initialCapacity),
        "Initial capacity cannot exceed maximum capacity(both rounded up to a power of 2)");
    maxQueueCapacity = ((long) ceilingPowerOfTwo(maxCapacity)) << 1;
  }
}

@SuppressWarnings({"MultiVariableDeclaration", "PMD.OneDeclarationPerLine"})
abstract class BaseMpscLinkedArrayQueuePad1<E> extends AbstractQueue<E> {
  byte p000, p001, p002, p003, p004, p005, p006, p007;
  byte p008, p009, p010, p011, p012, p013, p014, p015;
  byte p016, p017, p018, p019, p020, p021, p022, p023;
  byte p024, p025, p026, p027, p028, p029, p030, p031;
  byte p032, p033, p034, p035, p036, p037, p038, p039;
  byte p040, p041, p042, p043, p044, p045, p046, p047;
  byte p048, p049, p050, p051, p052, p053, p054, p055;
  byte p056, p057, p058, p059, p060, p061, p062, p063;
  byte p064, p065, p066, p067, p068, p069, p070, p071;
  byte p072, p073, p074, p075, p076, p077, p078, p079;
  byte p080, p081, p082, p083, p084, p085, p086, p087;
  byte p088, p089, p090, p091, p092, p093, p094, p095;
  byte p096, p097, p098, p099, p100, p101, p102, p103;
  byte p104, p105, p106, p107, p108, p109, p110, p111;
  byte p112, p113, p114, p115, p116, p117, p118, p119;
}

abstract class BaseMpscLinkedArrayQueueProducerFields<E> extends BaseMpscLinkedArrayQueuePad1<E> {
  protected long producerIndex;
}

@SuppressWarnings({"MultiVariableDeclaration",
    "OvershadowingSubclassFields", "PMD.OneDeclarationPerLine"})
abstract class BaseMpscLinkedArrayQueuePad2<E> extends BaseMpscLinkedArrayQueueProducerFields<E> {
  byte p000, p001, p002, p003, p004, p005, p006, p007;
  byte p008, p009, p010, p011, p012, p013, p014, p015;
  byte p016, p017, p018, p019, p020, p021, p022, p023;
  byte p024, p025, p026, p027, p028, p029, p030, p031;
  byte p032, p033, p034, p035, p036, p037, p038, p039;
  byte p040, p041, p042, p043, p044, p045, p046, p047;
  byte p048, p049, p050, p051, p052, p053, p054, p055;
  byte p056, p057, p058, p059, p060, p061, p062, p063;
  byte p064, p065, p066, p067, p068, p069, p070, p071;
  byte p072, p073, p074, p075, p076, p077, p078, p079;
  byte p080, p081, p082, p083, p084, p085, p086, p087;
  byte p088, p089, p090, p091, p092, p093, p094, p095;
  byte p096, p097, p098, p099, p100, p101, p102, p103;
  byte p104, p105, p106, p107, p108, p109, p110, p111;
  byte p112, p113, p114, p115, p116, p117, p118, p119;
}

abstract class BaseMpscLinkedArrayQueueConsumerFields<E> extends BaseMpscLinkedArrayQueuePad2<E> {
  protected @Nullable E[] consumerBuffer;
  protected long consumerIndex;
  protected long consumerMask;

  BaseMpscLinkedArrayQueueConsumerFields(int initialCapacity) {
    requireArgument(initialCapacity >= 2, "Initial capacity must be 2 or more");
    int p2capacity = ceilingPowerOfTwo(initialCapacity);
    // leave lower bit of mask clear
    long mask = (p2capacity - 1L) << 1;
    // need extra element to point at next array
    @Nullable E[] buffer = allocate(p2capacity + 1);
    consumerBuffer = buffer;
    consumerMask = mask;
  }

  @SuppressWarnings("unchecked")
  public static <E> @Nullable E[] allocate(int capacity) {
    return (E[]) new Object[capacity];
  }
}

@SuppressWarnings({"MultiVariableDeclaration",
    "OvershadowingSubclassFields", "PMD.OneDeclarationPerLine"})
abstract class BaseMpscLinkedArrayQueuePad3<E> extends BaseMpscLinkedArrayQueueConsumerFields<E> {
  byte p000, p001, p002, p003, p004, p005, p006, p007;
  byte p008, p009, p010, p011, p012, p013, p014, p015;
  byte p016, p017, p018, p019, p020, p021, p022, p023;
  byte p024, p025, p026, p027, p028, p029, p030, p031;
  byte p032, p033, p034, p035, p036, p037, p038, p039;
  byte p040, p041, p042, p043, p044, p045, p046, p047;
  byte p048, p049, p050, p051, p052, p053, p054, p055;
  byte p056, p057, p058, p059, p060, p061, p062, p063;
  byte p064, p065, p066, p067, p068, p069, p070, p071;
  byte p072, p073, p074, p075, p076, p077, p078, p079;
  byte p080, p081, p082, p083, p084, p085, p086, p087;
  byte p088, p089, p090, p091, p092, p093, p094, p095;
  byte p096, p097, p098, p099, p100, p101, p102, p103;
  byte p104, p105, p106, p107, p108, p109, p110, p111;
  byte p112, p113, p114, p115, p116, p117, p118, p119;

  BaseMpscLinkedArrayQueuePad3(int initialCapacity) {
    super(initialCapacity);
  }
}

abstract class BaseMpscLinkedArrayQueueColdProducerFields<E>
    extends BaseMpscLinkedArrayQueuePad3<E> {
  protected @Nullable E[] producerBuffer;
  protected volatile long producerLimit;
  protected long producerMask;

  BaseMpscLinkedArrayQueueColdProducerFields(int initialCapacity) {
    super(initialCapacity);
    producerMask = consumerMask;
    producerBuffer = consumerBuffer;
  }
}

abstract class BaseMpscLinkedArrayQueue<E> extends BaseMpscLinkedArrayQueueColdProducerFields<E> {
  static final VarHandle P_INDEX = findVarHandle(
      BaseMpscLinkedArrayQueueProducerFields.class, "producerIndex", long.class);
  static final VarHandle C_INDEX = findVarHandle(
      BaseMpscLinkedArrayQueueConsumerFields.class, "consumerIndex", long.class);
  static final VarHandle P_LIMIT = findVarHandle(
      BaseMpscLinkedArrayQueueColdProducerFields.class, "producerLimit", long.class);
  static final VarHandle REF_ARRAY = MethodHandles.arrayElementVarHandle(Object[].class);

  // No post padding here, subclasses must add

  private static final Object JUMP = new Object();

  /**
   * @param initialCapacity the queue initial capacity. If chunk size is fixed this will be the
   *        chunk size. Must be 2 or more.
   */
  BaseMpscLinkedArrayQueue(int initialCapacity) {
    super(initialCapacity);
    soProducerLimit(this, producerMask); // we know it's all empty to start with
  }

  @Override
  public final Iterator<E> iterator() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String toString() {
    return getClass().getName() + "@" + Integer.toHexString(hashCode());
  }

  @Override
  @SuppressWarnings({"MissingDefault", "PMD.NonExhaustiveSwitch"})
  public boolean offer(E e) {
    requireNonNull(e);

    @Var long mask;
    @Var long pIndex;
    @Var @Nullable E[] buffer;

    while (true) {
      long producerLimit = lvProducerLimit();
      pIndex = lvProducerIndex(this);
      // lower bit is indicative of resize, if we see it we spin until it's cleared
      if ((pIndex & 1) == 1) {
        continue;
      }
      // pIndex is even (lower bit is 0) -> actual index is (pIndex >> 1)

      // mask/buffer may get changed by resizing -> only use for array access after successful CAS.
      mask = this.producerMask;
      buffer = this.producerBuffer;
      // a successful CAS ties the ordering, lv(pIndex)-[mask/buffer]->cas(pIndex)

      // assumption behind this optimization is that queue is almost always empty or near empty
      if (producerLimit <= pIndex) {
        int result = offerSlowPath(mask, pIndex, producerLimit);
        switch (result) {
          case 0:
            break;
          case 1:
            continue;
          case 2:
            return false;
          case 3:
            resize(mask, buffer, pIndex, e);
            return true;
        }
      }

      if (casProducerIndex(this, pIndex, pIndex + 2)) {
        break;
      }
    }
    // INDEX visible before ELEMENT, consistent with consumer expectation
    long offset = modifiedCalcElementOffset(pIndex, mask);
    soElement(buffer, offset, e);
    return true;
  }

  /**
   * We do not inline resize into this method because we do not resize on fill.
   */
  int offerSlowPath(long mask, long pIndex, long producerLimit) {
    @Var int result;
    long cIndex = lvConsumerIndex(this);
    long bufferCapacity = getCurrentBufferCapacity(mask);
    result = 0;// 0 - goto pIndex CAS
    if (cIndex + bufferCapacity > pIndex) {
      if (!casProducerLimit(this, producerLimit, cIndex + bufferCapacity)) {
        result = 1;// retry from top
      }
    }
    // full and cannot grow
    else if (availableInQueue(pIndex, cIndex) <= 0) {
      result = 2;// -> return false;
    }
    // grab index for resize -> set lower bit
    else if (casProducerIndex(this, pIndex, pIndex + 1)) {
      result = 3;// -> resize
    } else {
      result = 1;// failed resize attempt, retry from top
    }
    return result;
  }

  /**
   * @return available elements in queue * 2
   */
  protected abstract long availableInQueue(long pIndex, long cIndex);

  /**
   * {@inheritDoc}
   * <p>
   * This implementation is correct for single consumer thread use only.
   */
  @Override
  @SuppressWarnings({"CastCanBeRemovedNarrowingVariableType", "PMD.ConfusingTernary", "unchecked"})
  public @Nullable E poll() {
    @Nullable E[] buffer = consumerBuffer;
    long index = consumerIndex;
    long mask = consumerMask;

    long offset = modifiedCalcElementOffset(index, mask);
    @Var @Nullable Object e = lvElement(buffer, offset);// LoadLoad
    if (e == null) {
      if (index != lvProducerIndex(this)) {
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
      @Nullable E[] nextBuffer = getNextBuffer(buffer, mask);
      return newBufferPoll(nextBuffer, index);
    }
    soElement(buffer, offset, null);
    soConsumerIndex(this, index + 2);
    return (E) e;
  }

  /**
   * {@inheritDoc}
   * <p>
   * This implementation is correct for single consumer thread use only.
   */
  @Override
  @SuppressWarnings({"CastCanBeRemovedNarrowingVariableType",
    "PMD.EmptyControlStatement", "unchecked"})
  public @Nullable E peek() {
    @Nullable E[] buffer = consumerBuffer;
    long index = consumerIndex;
    long mask = consumerMask;

    long offset = modifiedCalcElementOffset(index, mask);
    @Var @Nullable Object e = lvElement(buffer, offset);// LoadLoad
    if (e == null && index != lvProducerIndex(this)) {
      // peek() == null iff queue is empty, null element is not strong enough indicator, so we must
      // check the producer index. If the queue is indeed not empty we spin until element is
      // visible.
      while ((e = lvElement(buffer, offset)) == null) {
        // retry
      }
    }
    if (e == JUMP) {
      return newBufferPeek(getNextBuffer(buffer, mask), index);
    }
    return (E) e;
  }

  @SuppressWarnings("unchecked")
  private @Nullable E[] getNextBuffer(@Nullable E[] buffer, long mask) {
    long nextArrayOffset = nextArrayOffset(mask);
    var nextBuffer = (@Nullable E[]) lvElement(buffer, nextArrayOffset);
    soElement(buffer, nextArrayOffset, null);
    return requireNonNull(nextBuffer);
  }

  private static long nextArrayOffset(long mask) {
    return modifiedCalcElementOffset(mask + 2, Long.MAX_VALUE);
  }

  private E newBufferPoll(@Nullable E[] nextBuffer, long index) {
    long offsetInNew = newBufferAndOffset(nextBuffer, index);
    @Nullable E n = lvElement(nextBuffer, offsetInNew);// LoadLoad
    requireNonNull(n, "new buffer must have at least one element");
    soElement(nextBuffer, offsetInNew, null);// StoreStore
    soConsumerIndex(this, index + 2);
    return n;
  }

  private E newBufferPeek(@Nullable E[] nextBuffer, long index) {
    long offsetInNew = newBufferAndOffset(nextBuffer, index);
    @Nullable E n = lvElement(nextBuffer, offsetInNew);// LoadLoad
    requireNonNull(n, "new buffer must have at least one element");
    return n;
  }

  private long newBufferAndOffset(@Nullable E[] nextBuffer, long index) {
    consumerBuffer = nextBuffer;
    consumerMask = (nextBuffer.length - 2L) << 1;
    return modifiedCalcElementOffset(index, consumerMask);
  }

  @Override
  public final int size() {
    // NOTE: because indices are on even numbers we cannot use the size util.

    /*
     * It is possible for a thread to be interrupted or reschedule between the read of the producer
     * and consumer indices, therefore protection is required to ensure size is within valid range.
     * In the event of concurrent polls/offers to this method the size is OVER estimated as we read
     * consumer index BEFORE the producer index.
     */
    @Var long after = lvConsumerIndex(this);
    long size;
    while (true) {
      long before = after;
      long currentProducerIndex = lvProducerIndex(this);
      after = lvConsumerIndex(this);
      if (before == after) {
        size = ((currentProducerIndex - after) >> 1);
        break;
      }
    }
    // Long overflow is impossible, so size is always positive. Integer overflow is possible for the
    // unbounded indexed queues.
    return (int) Math.min(size, Integer.MAX_VALUE);
  }

  @Override
  public final boolean isEmpty() {
    // Order matters!
    // Loading consumer before producer allows for producer increments after consumer index is read.
    // This ensures this method is conservative in its estimate. Note that as this is an MPMC there
    // is nothing we can do to make this an exact method.
    return (lvConsumerIndex(this) == lvProducerIndex(this));
  }

  private long lvProducerLimit() {
    return producerLimit;
  }

  public long currentProducerIndex() {
    return lvProducerIndex(this) / 2;
  }

  public long currentConsumerIndex() {
    return lvConsumerIndex(this) / 2;
  }

  public abstract int capacity();

  public boolean relaxedOffer(E e) {
    return offer(e);
  }

  @SuppressWarnings({"CastCanBeRemovedNarrowingVariableType", "unchecked"})
  public @Nullable E relaxedPoll() {
    @Nullable E[] buffer = consumerBuffer;
    long index = consumerIndex;
    long mask = consumerMask;

    long offset = modifiedCalcElementOffset(index, mask);
    @Nullable Object e = lvElement(buffer, offset);// LoadLoad
    if (e == null) {
      return null;
    }
    if (e == JUMP) {
      @Nullable E[] nextBuffer = getNextBuffer(buffer, mask);
      return newBufferPoll(nextBuffer, index);
    }
    soElement(buffer, offset, null);
    soConsumerIndex(this, index + 2);
    return (E) e;
  }

  @SuppressWarnings({"CastCanBeRemovedNarrowingVariableType", "unchecked"})
  public @Nullable E relaxedPeek() {
    @Nullable E[] buffer = consumerBuffer;
    long index = consumerIndex;
    long mask = consumerMask;

    long offset = modifiedCalcElementOffset(index, mask);
    @Nullable Object e = lvElement(buffer, offset);// LoadLoad
    if (e == JUMP) {
      return newBufferPeek(getNextBuffer(buffer, mask), index);
    }
    return (E) e;
  }

  private void resize(long oldMask, @Nullable E[] oldBuffer, long pIndex, E e) {
    int newBufferLength = getNextBufferSize(oldBuffer);
    @Nullable E[] newBuffer = allocate(newBufferLength);

    producerBuffer = newBuffer;
    int newMask = (newBufferLength - 2) << 1;
    producerMask = newMask;

    long offsetInOld = modifiedCalcElementOffset(pIndex, oldMask);
    long offsetInNew = modifiedCalcElementOffset(pIndex, newMask);

    soElement(newBuffer, offsetInNew, e);// element in new array
    soElement(oldBuffer, nextArrayOffset(oldMask), newBuffer);// buffer linked

    // ASSERT code
    long cIndex = lvConsumerIndex(this);
    long availableInQueue = availableInQueue(pIndex, cIndex);
    requireState(availableInQueue > 0);

    // Invalidate racing CASs
    // We never set the limit beyond the bounds of a buffer
    soProducerLimit(this, pIndex + Math.min(newMask, availableInQueue));

    // make resize visible to the other producers
    soProducerIndex(this, pIndex + 2);

    // INDEX visible before ELEMENT, consistent with consumer expectation

    // make resize visible to consumer
    soElement(oldBuffer, offsetInOld, JUMP);
  }

  /**
   * @return next buffer size(inclusive of next array pointer)
   */
  protected abstract int getNextBufferSize(@Nullable E[] buffer);

  /**
   * @return current buffer capacity for elements (excluding next pointer and jump entry) * 2
   */
  protected abstract long getCurrentBufferCapacity(long mask);

  @SuppressWarnings("PMD.LooseCoupling")
  static long lvProducerIndex(BaseMpscLinkedArrayQueue<?> self) {
    return (long) P_INDEX.getVolatile(self);
  }
  @SuppressWarnings("PMD.LooseCoupling")
  static long lvConsumerIndex(BaseMpscLinkedArrayQueue<?> self) {
    return (long) C_INDEX.getVolatile(self);
  }
  @SuppressWarnings("PMD.LooseCoupling")
  static void soProducerIndex(BaseMpscLinkedArrayQueue<?> self, long v) {
    P_INDEX.setRelease(self, v);
  }
  @SuppressWarnings("PMD.LooseCoupling")
  static boolean casProducerIndex(BaseMpscLinkedArrayQueue<?> self, long expect, long newValue) {
    return P_INDEX.compareAndSet(self, expect, newValue);
  }
  @SuppressWarnings("PMD.LooseCoupling")
  static void soConsumerIndex(BaseMpscLinkedArrayQueue<?> self, long v) {
    C_INDEX.setRelease(self, v);
  }
  @SuppressWarnings("PMD.LooseCoupling")
  static boolean casProducerLimit(BaseMpscLinkedArrayQueue<?> self, long expect, long newValue) {
    return P_LIMIT.compareAndSet(self, expect, newValue);
  }
  @SuppressWarnings("PMD.LooseCoupling")
  static void soProducerLimit(BaseMpscLinkedArrayQueue<?> self, long v) {
    P_LIMIT.setRelease(self, v);
  }

  /*
   * A concurrent access enabling class used by circular array based queues this class exposes an
   * offset computation method along with differently memory fenced load/store methods into the
   * underlying array. The class is pre-padded and the array is padded on either side to help with
   * False sharing prevention. It is expected that subclasses handle post padding.
   * <p>
   * Offset calculation is separate from access to enable the reuse of a give compute offset.
   * <p>
   * Load/Store methods using a <i>buffer</i> parameter are provided to allow the prevention of
   * final field reload after a LoadLoad barrier.
   * <p>
   */

  /**
   * An ordered store(store + StoreStore barrier) of an element to a given offset
   *
   * @param buffer this.buffer
   * @param offset computed
   * @param e an orderly kitty
   */
  static <E> void soElement(@Nullable E[] buffer, long offset, @Nullable E e) {
    REF_ARRAY.setRelease(buffer, (int) offset, e);
  }

  /**
   * A volatile load (load + LoadLoad barrier) of an element from a given offset.
   *
   * @param buffer this.buffer
   * @param offset computed
   * @return the element at the offset
   */
  @SuppressWarnings("unchecked")
  static <E> @Nullable E lvElement(@Nullable E @Nullable[] buffer, long offset) {
    return (E) REF_ARRAY.getVolatile(buffer, (int) offset);
  }

  /**
   * This method assumes index is actually (index << 1) because lower bit is used for resize. This
   * is compensated for by reducing the element shift. The computation is constant folded, so
   * there's no cost.
   */
  static long modifiedCalcElementOffset(long index, long mask) {
    return (index & mask) >> 1;
  }

  static VarHandle findVarHandle(Class<?> recv, String name, Class<?> type) {
    try {
      Lookup lookup = MethodHandles.privateLookupIn(recv, MethodHandles.lookup());
      return lookup.findVarHandle(recv, name, type);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
