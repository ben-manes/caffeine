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

import static com.google.common.truth.Truth.assertThat;

import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JavaSerializationCopierTest {

  @Test(dataProvider = "nullArgs", expectedExceptions = NullPointerException.class)
  public void constructor_null(Set<Class<?>> immutableClasses,
      Map<Class<?>, Function<Object, Object>> deepCopyStrategies) {
    new JavaSerializationCopier(immutableClasses, deepCopyStrategies);
  }

  @Test(dataProvider = "copier", expectedExceptions = NullPointerException.class)
  public void null_object(Copier copier) {
    copy(copier, null);
  }

  @Test(dataProvider = "copier", expectedExceptions = NullPointerException.class)
  public void null_classLoader(Copier copier) {
    copier.copy(1, null);
  }

  @Test(dataProvider = "copier", expectedExceptions = UncheckedIOException.class)
  public void notSerializable(Copier copier) {
    copy(copier, new ByteArrayInputStream(new byte[0]));
  }

  @Test(dataProvider = "copier")
  public void mutable(Copier copier) {
    var ints = new ArrayList<>(Arrays.asList(0, 1, 2, 3, 4));
    assertThat(copy(copier, ints)).containsExactlyElementsIn(ints).inOrder();
  }

  @Test
  public void immutable() {
    String text = "test";
    assertThat(copy(new JavaSerializationCopier(), text)).isSameInstanceAs(text);
  }

  @Test(dataProvider = "copier")
  @SuppressWarnings({"JdkObsolete", "JavaUtilDate", "UndefinedEquals"})
  public void deepCopy_date(Copier copier) {
    Date date = new Date();
    assertThat(copy(copier, date)).isEqualTo(date);
  }

  @Test(dataProvider = "copier")
  @SuppressWarnings({"JdkObsolete", "JavaUtilDate"})
  public void deepCopy_calendar(Copier copier) {
    Calendar calendar = Calendar.getInstance();
    calendar.setTime(new Date());
    assertThat(copy(copier, calendar)).isEqualTo(calendar);
  }

  @Test(dataProvider = "copier")
  public void array_primitive(Copier copier) {
    int[] ints = { 0, 1, 2, 3, 4 };
    assertThat(copy(copier, ints)).isEqualTo(ints);
  }

  @Test(dataProvider = "copier")
  public void array_immutable(Copier copier) {
    Integer[] ints = { 0, 1, 2, 3, 4 };
    assertThat(copy(copier, ints)).asList().containsExactlyElementsIn(ints).inOrder();
  }

  @Test(dataProvider = "copier")
  public void array_mutable(Copier copier) {
    Object[] array = { new ArrayList<>(List.of(0, 1, 2, 3, 4)) };
    assertThat(copy(copier, array)).asList().containsExactlyElementsIn(array).inOrder();
  }

  private <T> T copy(Copier copier, T object) {
    return copier.copy(object, Thread.currentThread().getContextClassLoader());
  }

  @DataProvider(name = "copier")
  public Object[] providesCopiers() {
    return new Object[] {
        new JavaSerializationCopier(),
        new JavaSerializationCopier(Set.of(), Map.of())
    };
  }

  @DataProvider(name = "nullArgs")
  public Object[][] providesNullArgs() {
    return new Object[][] { { null, null }, { null, Map.of() }, { Set.of(), null } };
  }
}
