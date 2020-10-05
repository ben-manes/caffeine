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
package com.github.benmanes.caffeine.cache;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class UnsafeAccessTest {

  @Test
  public void load_fallback() throws Exception {
    assertThat(UnsafeAccess.load("abc", UnsafeAccess.OPEN_JDK), is(UnsafeAccess.UNSAFE));
    assertThat(UnsafeAccess.load("abc", "efg"), is(not(UnsafeAccess.UNSAFE)));
  }

  @Test
  public void objectFieldOffset() {
    assertThat(UnsafeAccess.objectFieldOffset(RelaxedFields.class, "ivalue"), is(greaterThan(0L)));
  }

  @Test(expectedExceptions = Error.class)
  public void objectFieldOffset_error() {
    UnsafeAccess.objectFieldOffset(getClass(), "foobar");
  }

  @Test(dataProvider = "relaxedFields")
  public void relaxed_ivalue(RelaxedFields relaxedFields) {
    relaxedFields.setRelaxedInt(100);
    int read = relaxedFields.getRelaxedInt();
    assertThat(relaxedFields.ivalue, is(100));
    assertThat(read, is(100));
  }

  @Test(dataProvider = "relaxedFields")
  public void relaxed_lvalue(RelaxedFields relaxedFields) {
    relaxedFields.setRelaxedLong(100L);
    long read = relaxedFields.getRelaxedLong();
    assertThat(relaxedFields.lvalue, is(100L));
    assertThat(read, is(100L));
  }

  @Test(dataProvider = "relaxedFields")
  public void relaxed_ovalue(MoreRelaxedFields relaxedFields) {
    Object o = new Object();
    relaxedFields.setRelaxedObject(o);
    Object read = relaxedFields.getRelaxedObject();
    assertThat(relaxedFields.ovalue, is(o));
    assertThat(read, is(o));
  }

  @DataProvider(name = "relaxedFields")
  public Object[][] providesRelaxedFields() {
    return new Object[][] {{ new MoreRelaxedFields() }};
  }

  static class RelaxedFields {
    static final long IVALUE_OFFSET = UnsafeAccess.objectFieldOffset(RelaxedFields.class, "ivalue");
    static final long LVALUE_OFFSET = UnsafeAccess.objectFieldOffset(RelaxedFields.class, "lvalue");

    private volatile int ivalue;
    private volatile long lvalue;

    void setRelaxedInt(int value) {
      UnsafeAccess.UNSAFE.putOrderedInt(this, RelaxedFields.IVALUE_OFFSET, value);
    }

    int getRelaxedInt() {
      return UnsafeAccess.UNSAFE.getInt(this, RelaxedFields.IVALUE_OFFSET);
    }

    void setRelaxedLong(long value) {
      UnsafeAccess.UNSAFE.putOrderedLong(this, RelaxedFields.LVALUE_OFFSET, value);
    }

    long getRelaxedLong() {
      return UnsafeAccess.UNSAFE.getInt(this, RelaxedFields.LVALUE_OFFSET);
    }
  }

  static final class MoreRelaxedFields extends RelaxedFields {
    static final long OVALUE_OFFSET =
        UnsafeAccess.objectFieldOffset(MoreRelaxedFields.class, "ovalue");

    private volatile Object ovalue;

    void setRelaxedObject(Object value) {
      UnsafeAccess.UNSAFE.putOrderedObject(this, MoreRelaxedFields.OVALUE_OFFSET, value);
    }

    Object getRelaxedObject() {
      return UnsafeAccess.UNSAFE.getObject(this, MoreRelaxedFields.OVALUE_OFFSET);
    }
  }
}
