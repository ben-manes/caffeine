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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.tracing.TraceEvent.Action;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TraceEventTest {
  final Random random = new Random();

  @Test(dataProvider = "events")
  public void text(TraceEvent event) throws IOException {
    StringBuilder output = new StringBuilder();
    TraceEventFormats.writeTextRecord(event, output);
    TraceEvent recorded = TraceEventFormats.readTextRecord(output.toString().trim().split(" "));

    assertEqualEvents(recorded, event);
  }

  @Test(dataProvider = "events")
  public void binary(TraceEvent event) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream output = new DataOutputStream(bytes);
    TraceEventFormats.writeBinaryRecord(event, output);

    DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    TraceEvent recorded = TraceEventFormats.readBinaryRecord(input);

    assertEqualEvents(recorded, event);
  }

  @Test
  private void construction() {
    TraceEvent first = new TraceEvent(null, random.nextLong(),
        Action.READ, random.nextInt(), random.nextInt(), random.nextLong());
    TraceEvent second = new TraceEvent();
    second.setKeyHash(first.keyHash());
    second.setAction(first.action());
    second.setId(first.id());
    second.setTimestamp(first.timestamp());
    assertEqualEvents(first, second);
  }

  @DataProvider(name = "events")
  public Iterator<Object[]> providesEvents() {
    List<Object[]> events = new ArrayList<>();
    for (Action action : Action.values()) {
      TraceEvent event;
      if (action == Action.REGISTER) {
        event = new TraceEvent("test", random.nextLong(), action, 0, 0, 0L);
      } else {
        event = new TraceEvent(null, random.nextLong(),
            action, random.nextInt(), random.nextInt(), random.nextLong());
      }
      events.add(new Object[] { event });
    }
    return events.iterator();
  }

  private static void assertEqualEvents(TraceEvent first, TraceEvent second) {
    assertThat(first.id(), is(second.id()));
    assertThat(first.name(), is(second.name()));
    assertThat(first.action(), is(second.action()));
    assertThat(first.weight(), is(second.weight()));
    assertThat(first.keyHash(), is(second.keyHash()));
    assertThat(first.timestamp(), is(second.timestamp()));
    assertThat(first, is(equalTo(second)));
    assertThat(first.toString(), is(second.toString()));
    assertThat(first.hashCode(), is(second.hashCode()));
    assertThat(first.equals(new Object()), is(false));
    assertThat(first.equals(first), is(true));
  }
}
