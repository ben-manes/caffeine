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
package com.github.benmanes.caffeine.cache.buffer;

import java.util.function.Consumer;

import org.jctools.queues.MpscCompoundQueue;

import com.github.benmanes.caffeine.cache.ReadBuffer;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class MpscCompoundBuffer<E> extends ReadBuffer<E> {
  final MpscCompoundQueue<E> queue;
  long drained;

  MpscCompoundBuffer() {
    queue = new MpscCompoundQueue<>(BUFFER_SIZE);
  }

  @Override
  public int offer(E e) {
    return queue.offer(e) ? SUCCESS : FULL;
  }

  @Override
  public void drainTo(Consumer<E> consumer) {
    E e = null;
    while ((e = queue.poll()) != null) {
      consumer.accept(e);
      drained++;
    }
  }

  @Override
  public long reads() {
    return drained;
  }

  @Override
  public long writes() {
    return drained() + queue.size();
  }
}
