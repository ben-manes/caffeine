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
package com.github.benmanes.caffeine.cache.tracing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.util.Random;

import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.tracing.CacheEvent.Action;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheEventTest {
  final Random random = new Random();

  @Test
  public void text() throws IOException {
    for (Action action : Action.values()) {
      CacheEvent event = new CacheEvent(random.nextInt(),
          action, random.nextInt(), random.nextLong());

      StringBuilder output = new StringBuilder();
      event.appendTextRecord(output);
      CacheEvent recorded = CacheEvent.fromTextRecord(output.toString());

      checkEquals(recorded, event);
    }
  }

  @Test
  private void construction() {
    CacheEvent first = new CacheEvent(random.nextInt(),
        Action.READ, random.nextInt(), random.nextLong());
    CacheEvent second = new CacheEvent();
    second.setHash(first.hash());
    second.setAction(first.action());
    second.setCacheId(first.cacheId());
    second.setTimestamp(first.timestamp());
    checkEquals(first, second);
  }

  private static void checkEquals(CacheEvent first, CacheEvent second) {
    assertThat(first.timestamp(), is(second.timestamp()));
    assertThat(first.cacheId(), is(second.cacheId()));
    assertThat(first.action(), is(second.action()));
    assertThat(first.hash(), is(second.hash()));
    assertThat(first, is(equalTo(second)));
    assertThat(first.hashCode(), is(second.hashCode()));
  }
}
