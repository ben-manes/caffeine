/*
 * Copyright 2026 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache;

import static java.lang.invoke.ConstantBootstraps.fieldVarHandle;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.concurrent.Executor;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.IL_Result;
import org.openjdk.jcstress.infra.results.LI_Result;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * A stress test for the expiration read protocol. A reader judges expiry by the entry's timestamps
 * and then loads the value, while a writer that rewrites an expired entry stores the value and then
 * the fresh timestamps, with a fence on each side to hold the order. A reader that observes a fresh
 * timestamp is therefore guaranteed to observe the rewritten value. Without the pairing the reader
 * can return a value whose expiration was already announced to the removal listener: it loads the
 * old value, the rewrite installs a fresh write time, and the reader's expiry check then passes
 * against that fresh timestamp.
 * <p>
 * {@snippet lang="shell" :
 * ./gradlew caffeine:jcstress -PjavaVersion=21 --tests ExpiredReadTear --rerun
 * }
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressFBWarnings("URF_UNREAD_FIELD")
@SuppressWarnings({"JavadocDeclaration", "PMD.MissingStaticMethodInNonInstantiatableClass"})
public final class ExpiredReadTear {

  private ExpiredReadTear() {}

  @State
  @JCStressTest
  @Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "Stale timestamp with the old value")
  @Outcome(id = "1, 2", expect = Expect.ACCEPTABLE, desc = "Stale timestamp with the new value")
  @Outcome(id = "2, 2", expect = Expect.ACCEPTABLE, desc = "Fresh timestamp with the new value")
  @Outcome(id = "2, 1", expect = Expect.FORBIDDEN, desc = "Fresh timestamp with the old value")
  public static class Simple {
    private static final VarHandle TIME = fieldVarHandle(MethodHandles.lookup(),
        "time", VarHandle.class, Simple.class, long.class);
    private static final VarHandle VALUE = fieldVarHandle(MethodHandles.lookup(),
        "value", VarHandle.class, Simple.class, int.class);

    volatile long time = 1;
    volatile int value = 1;

    @Actor
    public void writer() {
      VALUE.setRelease(this, 2);
      VarHandle.storeStoreFence();
      TIME.setOpaque(this, 2L);
    }

    @Actor
    public void reader(LI_Result r) {
      r.r1 = (long) TIME.getOpaque(this);
      VarHandle.loadLoadFence();
      r.r2 = (int) VALUE.getAcquire(this);
    }
  }

  @State
  @JCStressTest
  @Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "Old value with its own timestamp")
  @Outcome(id = "2, 1", expect = Expect.ACCEPTABLE, desc = "New value with the stale timestamp")
  @Outcome(id = "2, 2", expect = Expect.ACCEPTABLE, desc = "New value with the fresh timestamp")
  @Outcome(id = "1, 2", expect = Expect.ACCEPTABLE_INTERESTING,
      desc = "The read tear: old value judged by the fresh timestamp")
  public static class Racy {
    private static final VarHandle TIME = fieldVarHandle(MethodHandles.lookup(),
        "time", VarHandle.class, Racy.class, long.class);
    private static final VarHandle VALUE = fieldVarHandle(MethodHandles.lookup(),
        "value", VarHandle.class, Racy.class, int.class);

    volatile long time = 1;
    volatile int value = 1;

    @Actor
    public void writer() {
      TIME.set(this, 2L);
      VALUE.setRelease(this, 2);
    }

    @Actor
    public void reader(IL_Result r) {
      r.r1 = (int) VALUE.getAcquire(this);
      r.r2 = (long) TIME.getOpaque(this);
    }
  }

  @State
  @JCStressTest
  @Outcome(id = "0, 0", expect = Expect.ACCEPTABLE, desc = "Read missed the expired entry")
  @Outcome(id = "0, 2", expect = Expect.ACCEPTABLE, desc = "Read observed the rewrite")
  @Outcome(id = "0, 1", expect = Expect.FORBIDDEN, desc = "Read returned the expired value")
  @Outcome(expect = Expect.FORBIDDEN, desc = "The rewrite observed a live prior mapping")
  public static class Actual {
    private static final String KEY = "key";
    private static final String OLD = "old";
    private static final String NEW = "new";

    final Cache<String, String> cache;

    public Actual() {
      var now = new long[1];
      Executor discarding = task -> {};
      cache = Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofMinutes(1))
          .executor(discarding)
          .ticker(() -> now[0])
          .build();
      cache.put(KEY, OLD);
      now[0] = Duration.ofMinutes(2).toNanos();
    }

    @Actor
    public void writer(II_Result r) {
      r.r1 = (cache.asMap().put(KEY, NEW) == null) ? 0 : 1;
    }

    @Actor
    public void reader(II_Result r) {
      var value = cache.getIfPresent(KEY);
      r.r2 = (value == null) ? 0 : (value.equals(OLD) ? 1 : 2);
    }
  }
}
