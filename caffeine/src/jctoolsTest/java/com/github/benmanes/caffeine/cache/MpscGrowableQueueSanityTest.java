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
import java.util.List;
import java.util.Queue;

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * @author nitsanw@yahoo.com (Nitsan Wakart)
 */
@RunWith(Parameterized.class)
public final class MpscGrowableQueueSanityTest extends QueueSanityTest {

  public MpscGrowableQueueSanityTest(Queue<Integer> queue,
      Ordering ordering, int capacity, boolean isBounded) {
    super(queue, ordering, capacity, isBounded);
  }

  @Parameterized.Parameters
  public static List<Object[]> parameters() {
    var list = new ArrayList<Object[]>();
    // MPSC size: 1
    list.add(new Object[] { new MpscGrowableArrayQueue<>(2, 4), Ordering.FIFO, 4, true });
    // MPSC size: SIZE
    list.add(new Object[] { new MpscGrowableArrayQueue<>(8, SIZE), Ordering.FIFO, SIZE, true });
    return list;
  }
}
