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
package com.github.benmanes.caffeine.jcache.copy;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JavaSerializationCopierTest {
  final Copier copier = new JavaSerializationCopier();

  @Test(expectedExceptions = NullPointerException.class)
  public void null_object() {
    copy(null);
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void null_classLoader() {
    copier.copy(1, null);
  }

  @Test(expectedExceptions = UncheckedIOException.class)
  public void notSerializable() {
    copy(new ByteArrayInputStream(new byte[0]));
  }

  @Test
  public void mutable() {
    List<Integer> ints = new ArrayList<>(Arrays.asList(0, 1, 2, 3, 4));
    assertThat(copy(ints), is(equalTo(ints)));
  }

  @Test
  public void immutable() {
    String text = "test";
    assertThat(copy(text), is(sameInstance(text)));
  }

  @Test
  @SuppressWarnings({"JdkObsolete", "JavaUtilDate"})
  public void deepCopy_date() {
    Date date = new Date();
    assertThat(copy(date), is(equalTo(date)));
  }

  @Test
  @SuppressWarnings({"JdkObsolete", "JavaUtilDate"})
  public void deepCopy_calendar() {
    Calendar calendar = Calendar.getInstance();
    calendar.setTime(new Date());
    assertThat(copy(calendar), is(equalTo(calendar)));
  }

  @Test
  public void array_primitive() {
    int[] ints = { 0, 1, 2, 3, 4 };
    assertThat(copy(ints), is(equalTo(ints)));
  }

  @Test
  public void array_immutable() {
    Integer[] ints = { 0, 1, 2, 3, 4 };
    assertThat(copy(ints), is(equalTo(ints)));
  }

  @Test
  public void array_mutable() {
    Object array = new Object[] { new ArrayList<>(Arrays.asList(0, 1, 2, 3, 4)) };
    assertThat(copy(array), is(equalTo(array)));
  }

  private <T> T copy(T object) {
    return copier.copy(object, Thread.currentThread().getContextClassLoader());
  }
}
