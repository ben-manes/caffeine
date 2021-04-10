/*
 * Copyright 2019 Omri Himelbrand. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.address.penalties;

import java.util.Set;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.address.AddressTraceReader;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.google.common.collect.ImmutableSet;

/**
 * An extension to {@link AddressTraceReader} where the trace files were augmented to include hit
 * and miss penalties.
 * <p>
 * Example line in this format: <tt>s 0x1fffff50 1 200 1200</tt>
 * <p>
 * The description of each part of the line by order from left to right:
 * <ul>
 *   <li>Access Type: A single character indicating whether the access is a load (<tt>l</tt>) or a
 *       store (<tt>s</tt>).
 *   <li>Address: A 32-bit integer (in unsigned hexidecimal format) specifying the memory address
 *       that is being accessed. For example, <tt>0xff32e100</tt> specifies that memory address
 *       <tt>4281524480</tt> is accessed.
 *   <li>Instructions since last memory access: Indicates the number of instructions of any type
 *       that executed between since the last memory access (i.e. the one on the previous line in
 *       the trace). For example, if the 5th and 10th instructions in the program's execution are
 *       loads, and there are no memory operations between them, then the trace line for with the
 *       second load has <tt>4</tt> for this field.
 *   <li>Hit penalty: Indicates the hit penalty, the time took to handle the request when it was in
 *       the cache.
 *   <li>Miss penalty: Indicates the miss penalty, the time took to handle the request when it
 *       wasn't in the cache.
 * </ul>
 *
 * @author himelbrand@gmail.com (Omri Himelbrand)
 */
public final class AddressPenaltiesTraceReader extends TextTraceReader {

  public AddressPenaltiesTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Set<Characteristic> characteristics() {
    return ImmutableSet.of();
  }

  @Override
  public Stream<AccessEvent> events() {
    return lines()
        .map(line -> line.split(" ", 5))
        .map(split -> AccessEvent.forKeyAndPenalties(
            Long.parseLong(split[1].substring(2), 16),
            Double.parseDouble(split[3]),
            Double.parseDouble(split[4])));
  }
}
