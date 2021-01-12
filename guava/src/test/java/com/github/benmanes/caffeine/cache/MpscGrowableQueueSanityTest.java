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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * @author nitsanw@yahoo.com (Nitsan Wakart)
 */
@RunWith(Parameterized.class)
@SuppressWarnings("deprecation")
public final class MpscGrowableQueueSanityTest extends QueueSanityTest {

  public MpscGrowableQueueSanityTest(
      org.jctools.queues.spec.ConcurrentQueueSpec spec, Queue<Integer> queue) {
    super(spec, queue);
  }

  @Parameterized.Parameters
  public static Collection<Object[]> parameters() {
    List<Object[]> list = new ArrayList<Object[]>();
    addVarHandle(list);
    addUnsafe(list);
    return list;
  }

  private static void addVarHandle(List<Object[]> list) {
    // MPSC size 1
    list.add(makeQueue(0, 1, 4, org.jctools.queues.spec.Ordering.FIFO,
        new VarHandleMpscGrowableArrayQueue<>(2, 4)));
    // MPSC size SIZE
    list.add(makeQueue(0, 1, SIZE, org.jctools.queues.spec.Ordering.FIFO,
        new VarHandleMpscGrowableArrayQueue<>(8, SIZE)));
  }

  private static void addUnsafe(List<Object[]> list) {
    // MPSC size 1
    list.add(makeQueue(0, 1, 4, org.jctools.queues.spec.Ordering.FIFO,
        new UnsafeMpscGrowableArrayQueue<>(2, 4)));
    // MPSC size SIZE
    list.add(makeQueue(0, 1, SIZE, org.jctools.queues.spec.Ordering.FIFO,
        new UnsafeMpscGrowableArrayQueue<>(8, SIZE)));
  }

  static final class VarHandleMpscGrowableArrayQueue<E> extends MpscGrowableArrayQueue<E> {
    static final MpscAccess ACCESS = new VarHandleMpscAccess();

    VarHandleMpscGrowableArrayQueue(int initialCapacity, int maxCapacity) {
      super(initialCapacity, maxCapacity);
    }
    @Override
    protected MpscAccess getAccess() {
      return ACCESS;
    }
  }

  static final class UnsafeMpscGrowableArrayQueue<E> extends MpscGrowableArrayQueue<E> {
    static final MpscAccess ACCESS = new UnsafeMpscAccess();

    UnsafeMpscGrowableArrayQueue(int initialCapacity, int maxCapacity) {
      super(initialCapacity, maxCapacity);
    }
    @Override
    protected MpscAccess getAccess() {
      return ACCESS;
    }
  }
}
