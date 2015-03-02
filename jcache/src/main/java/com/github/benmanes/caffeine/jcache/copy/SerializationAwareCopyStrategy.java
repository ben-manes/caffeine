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

import static java.util.Objects.requireNonNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

import javax.cache.CacheException;

/**
 * A strategy that uses Java serialization if the type is not a known immutable, an array of known
 * immutable types, or specially handled as a known cloning strategy.
 * <p>
 * Beware that native serialization is slow and is provided for completeness. In practice, it is
 * recommended that a higher performance alternative is used, which is provided by numerous external
 * libraries.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class SerializationAwareCopyStrategy implements CopyStrategy {
  private static final Map<Class<?>, Function<Object, Object>> JAVA_DEEP_COPY;
  private static final Set<Class<?>> JAVA_IMMUTABLE;

  static {
    JAVA_IMMUTABLE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(Boolean.class,
        Byte.class, Character.class, Double.class, Float.class, Short.class, Integer.class,
        Long.class, BigInteger.class, BigDecimal.class, String.class, Class.class, UUID.class,
        URL.class, URI.class, Pattern.class, Inet4Address.class, Inet6Address.class,
        InetSocketAddress.class, LocalDate.class, LocalTime.class, LocalDateTime.class,
        Instant.class, Duration.class)));
    Map<Class<?>, Function<Object, Object>> strategies = new HashMap<>();
    strategies.put(Date.class, o -> ((Calendar) o).clone());
    strategies.put(Date.class, o -> ((Date) o).clone());
    JAVA_DEEP_COPY = Collections.unmodifiableMap(strategies);
  }

  private final Set<Class<?>> immutableClasses;
  private final Map<Class<?>, Function<Object, Object>> deepCopyStrategies;

  public SerializationAwareCopyStrategy() {
    this(JAVA_IMMUTABLE, JAVA_DEEP_COPY);
  }

  public SerializationAwareCopyStrategy(Set<Class<?>> immutableClasses,
      Map<Class<?>, Function<Object, Object>> deepCopyStrategies) {
    this.immutableClasses = requireNonNull(immutableClasses);
    this.deepCopyStrategies = requireNonNull(deepCopyStrategies);
  }

  /** @return the set of Java native classes that are immutable */
  public static Set<Class<?>> javaImmutableClasses() {
    return JAVA_IMMUTABLE;
  }

  /** @return the set of Java native classes that are immutable. */
  public static Map<Class<?>, Function<Object, Object>> javaDeepCopyStrategies() {
    return JAVA_DEEP_COPY;
  }

  @Override
  public <T> T copy(T object, ClassLoader classLoader) {
    if (isImmutable(object.getClass())) {
      return object;
    } else if (canDeeplyCopy(object.getClass())) {
      return deepCopy(object);
    } else if (isArrayOfImmutableTypes(object.getClass())) {
      return arrayCopy(object);
    }
    byte[] data = serialize(object);
    @SuppressWarnings("unchecked")
    T copy = (T) deserialize(data, classLoader);
    return copy;
  }

  /**
   * Returns if the class is an immutable type and does not need to be copied.
   *
   * @param clazz the class of the object being copied
   * @return if the class is an immutable type and does not need to be copied
   */
  protected boolean isImmutable(Class<?> clazz) {
    return immutableClasses.contains(clazz) || clazz.isEnum();
  }

  /**
   * Returns if the class has a known deep copy strategy.
   *
   * @param clazz the class of the object being copied
   * @return if the class has a known deep copy strategy
   */
  protected boolean canDeeplyCopy(Class<?> clazz) {
    return deepCopyStrategies.containsKey(clazz);
  }

  /** @return if the class represents an array of immutable values. */
  private boolean isArrayOfImmutableTypes(Class<?> clazz) {
    if (!clazz.isArray()) {
      return false;
    }
    Class<?> component = clazz.getComponentType();
    return component.isPrimitive() || isImmutable(component);
  }

  /** @return a shallow copy of the array. */
  private <T> T arrayCopy(T object) {
    int length = Array.getLength(object);
    @SuppressWarnings("unchecked")
    T copy = (T) Array.newInstance(object.getClass().getComponentType(), length);
    System.arraycopy(object, 0, copy, 0, length);
    return copy;
  }

  /** @return a deep copy of the object. */
  private <T> T deepCopy(T object) {
    @SuppressWarnings("unchecked")
    T copy = (T) deepCopyStrategies.get(object.getClass()).apply(object);
    return copy;
  }

  /**
   * Serializes the object with Java native serialization.
   *
   * @param object the object to serialize
   * @return the serialized bytes
   */
  protected static byte[] serialize(Object object) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
      output.writeObject(object);
    } catch (IOException e) {
      throw new IllegalArgumentException("Failed to serialize " + e.getClass(), e);
    }
    return bytes.toByteArray();
  }

  /**
   * Deserializes the data using the provided classloader.
   *
   * @param data the serialized bytes
   * @param classLoader the classloader to create the instance with
   * @return the deserialized object
   */
  protected static Object deserialize(byte[] data, ClassLoader classLoader) {
    try (InputStream bytes = new ByteArrayInputStream(data);
        ObjectInputStream input = new ClassLoaderAwareObjectInputStream(bytes, classLoader)) {
      return input.readObject();
    } catch (IOException e) {
      throw new CacheException("Failed to deserialize", e);
    } catch (ClassNotFoundException e) {
      throw new CacheException("Failed to resolve a deserialized class", e);
    }
  }

  /** An {@linkplain ObjectInputStream} that instantiates using the supplied classloader. */
  protected static class ClassLoaderAwareObjectInputStream extends ObjectInputStream {
    private final ClassLoader classLoader;

    public ClassLoaderAwareObjectInputStream(InputStream in, ClassLoader classLoader)
        throws IOException {
      super(in);
      this.classLoader = requireNonNull(classLoader);
    }

    protected ClassLoader getClassLoader() {
      return classLoader;
    }

    @Override
    protected Class<?> resolveClass(ObjectStreamClass desc)
        throws IOException, ClassNotFoundException {
      try {
        return Class.forName(desc.getName(), false, getClassLoader());
      } catch (ClassNotFoundException ex) {
        return super.resolveClass(desc);
      }
    }
  }
}
