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
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.cache.CacheException;

/**
 * A strategy that uses Java serialization if a fast path approach is not applicable.
 * <p>
 * Beware that native serialization is slow and is provided for completeness. In practice, it is
 * recommended that a higher performance alternative is used, which is provided by numerous external
 * libraries.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class JavaSerializationCopier extends AbstractCopier<byte[]> {

  public JavaSerializationCopier() {
    super();
  }

  public JavaSerializationCopier(Set<Class<?>> immutableClasses,
      Map<Class<?>, Function<Object, Object>> deepCopyStrategies) {
    super(immutableClasses, deepCopyStrategies);
  }

  @Override
  protected byte[] serialize(Object object) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
      output.writeObject(object);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to serialize " + object.getClass(), e);
    }
    return bytes.toByteArray();
  }

  @Override
  @SuppressWarnings("BanSerializableRead")
  protected Object deserialize(byte[] data, ClassLoader classLoader) {
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
    @SuppressWarnings("BanSerializableRead")
    protected Class<?> resolveClass(ObjectStreamClass desc)
        throws IOException, ClassNotFoundException {
      try {
        return Class.forName(desc.getName(), /* initialize */ false, getClassLoader());
      } catch (ClassNotFoundException ex) {
        return super.resolveClass(desc);
      }
    }
  }
}
