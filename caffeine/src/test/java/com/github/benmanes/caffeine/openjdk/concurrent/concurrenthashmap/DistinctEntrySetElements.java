/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * Portions Copyright (c) 2011 IBM Corporation
 */
package com.github.benmanes.caffeine.openjdk.concurrent.concurrenthashmap;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/*
 * @test
 * @bug 6312706
 * @summary Sets from Map.entrySet() return distinct objects for each Entry
 * @author Neil Richards <neil.richards@ngmr.net>, <neil_richards@uk.ibm.com>
 */
@SuppressWarnings({"AlmostJavadoc", "YodaCondition"})
public class DistinctEntrySetElements {

    @Test
    public void bounded() {
      Cache<String, String> cache = Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofNanos(Long.MAX_VALUE))
          .maximumSize(Long.MAX_VALUE)
          .build();
      testDistinct(cache.asMap());
    }

    @Test
    public void unbounded() {
      Cache<String, String> cache = Caffeine.newBuilder().build();
      testDistinct(cache.asMap());
    }

    public static void testDistinct(ConcurrentMap<String, String> concurrentHashMap) {
        concurrentHashMap.put("One", "Un");
        concurrentHashMap.put("Two", "Deux");
        concurrentHashMap.put("Three", "Trois");

        Set<Map.Entry<String, String>> entrySet = concurrentHashMap.entrySet();
        HashSet<Map.Entry<String, String>> hashSet = new HashSet<>(entrySet);

        if (false == hashSet.equals(entrySet)) {
            throw new RuntimeException("Test FAILED: Sets are not equal.");
        }
        if (hashSet.hashCode() != entrySet.hashCode()) {
            throw new RuntimeException("Test FAILED: Set's hashcodes are not equal.");
        }
    }
}
