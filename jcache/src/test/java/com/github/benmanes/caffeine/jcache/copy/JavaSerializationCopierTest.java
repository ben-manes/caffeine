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

import static com.github.benmanes.caffeine.jcache.copy.AbstractCopier.javaDeepCopyStrategies;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Locale.US;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Function;

import javax.cache.CacheException;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableSet;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JavaSerializationCopierTest {

  @Test(dataProvider = "nullArgs")
  public void constructor_null(Set<Class<?>> immutableClasses,
      Map<Class<?>, Function<Object, Object>> deepCopyStrategies) {
    assertThrows(NullPointerException.class, () ->
        new JavaSerializationCopier(immutableClasses, deepCopyStrategies));
  }

  @Test(dataProvider = "copier")
  public void null_object(Copier copier) {
    assertThrows(NullPointerException.class, () -> copy(copier, null));
  }

  @Test(dataProvider = "copier")
  public void null_classLoader(Copier copier) {
    assertThrows(NullPointerException.class, () -> copier.copy(1, null));
  }

  @Test(dataProvider = "copier")
  public void serializable_fail(JavaSerializationCopier copier) {
    assertThrows(UncheckedIOException.class, () -> copier.serialize(new Object()));
  }

  @Test
  public void deserializable_resolveClass() {
    var copier = new JavaSerializationCopier();
    var copy = copier.copy(ImmutableSet.of(), ClassLoader.getPlatformClassLoader());
    assertThat(copy).isInstanceOf(ImmutableSet.class);
  }

  @Test(dataProvider = "copier")
  public void deserializable_badData(JavaSerializationCopier copier) {
    assertThrows(CacheException.class, () ->
        copier.deserialize(new byte[0], Thread.currentThread().getContextClassLoader()));
  }

  @Test
  public void deserializable_classNotFound() {
    var copier = new JavaSerializationCopier() {
      @Override protected ObjectInputStream newInputStream(
          InputStream in, ClassLoader classLoader) throws IOException {
        return new ObjectInputStream(in) {
          @Override protected Class<?> resolveClass(ObjectStreamClass desc)
              throws IOException, ClassNotFoundException {
            throw new ClassNotFoundException();
          }
        };
      }
    };
    assertThrows(CacheException.class, () ->
        copier.roundtrip(100, Thread.currentThread().getContextClassLoader()));
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

  @Test
  public void canDeeplyCopy() {
    var copier = new JavaSerializationCopier();
    assertThat(copier.canDeeplyCopy(Object.class)).isFalse();
    for (var clazz : javaDeepCopyStrategies().keySet()) {
      assertThat(copier.canDeeplyCopy(clazz)).isTrue();
    }
  }

  @Test(dataProvider = "copier")
  @SuppressWarnings({"JavaUtilDate", "JdkObsolete", "UndefinedEquals"})
  public void deepCopy_date(Copier copier) {
    Date date = new Date();
    assertThat(copy(copier, date)).isEqualTo(date);
  }

  @Test(dataProvider = "copier")
  @SuppressWarnings({"JavaUtilDate", "JdkObsolete"})
  public void deepCopy_calendar(Copier copier) {
    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), US);
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
