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
package com.github.benmanes.caffeine.cache.simulator.parser.penalties.address;

import com.github.benmanes.caffeine.cache.simulator.Characteristics;
import com.github.benmanes.caffeine.cache.simulator.parser.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A reader for the trace files of application address instructions, provided by
 * <a href="http://cseweb.ucsd.edu/classes/fa07/cse240a/project1.html">UC SD</a>, but with additional fields.
 *
 * Example line in this format: s 0x1fffff50 1 200 1200
 *
 * The description of each part of the line by order from left to right:
 * Access Type: A single character indicating whether the access is a load ('l') or a store ('s').
 * Address: A 32-bit integer (in unsigned hexidecimal format) specifying the memory address that is being accessed. For example, "0xff32e100" specifies that memory address 4281524480 is accessed.
 * Instructions since last memory access: Indicates the number of instructions of any type that executed between since the last memory access (i.e. the one on the previous line in the trace). For example, if the 5th and 10th instructions in the program's execution are loads, and there are no memory operations between them, then the trace line for with the second load has "4" for this field.
 * Hit penalty: Indicates the hit penalty, the time took to handle the request when it was in the cache
 * Miss penalty: Indicates the miss penalty, the time took to handle the request when it wasn't in the cache
 *
 * @author himelbrand@gmail.com (Omri Himelbrand)
 */
public final class AddressPenaltiesTraceReader extends TextTraceReader {

  public AddressPenaltiesTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public Stream<AccessEvent> events() throws IOException {
    return lines()
        .map(line -> line.split(" ", 5))
        .map(split -> split[1] + " " + split[3] + " " + split[4])
        .map(address_pen -> address_pen.substring(2).split(" ", 3))
        .map(address_pen -> new AccessEvent.AccessEventBuilder(Long.parseLong(address_pen[0], 16))
                                .setHitPenalty(Long.parseLong(address_pen[1]))
                                .setMissPenalty(Long.parseLong(address_pen[2])).build());
  }

  @Override
  public Set<Characteristics> getCharacteristicsSet() {
    return ImmutableSet.of(Characteristics.KEY,Characteristics.MISS_PENALTY,Characteristics.HIT_PENALTY);
  }
}
