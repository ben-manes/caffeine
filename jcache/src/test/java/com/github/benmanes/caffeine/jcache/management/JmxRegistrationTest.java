/*
 * Copyright 2022 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.jcache.management;

import static com.github.benmanes.caffeine.jcache.JCacheFixture.nullRef;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.util.Set;

import javax.cache.CacheException;
import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.JMException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class JmxRegistrationTest {

  @ParameterizedTest
  @ValueSource(classes = {InstanceAlreadyExistsException.class,
      MBeanRegistrationException.class, NotCompliantMBeanException.class})
  void register_error(Class<? extends Throwable> throwableType) throws JMException {
    var name = new ObjectName("");
    var bean = new JCacheStatisticsMXBean();
    MBeanServer server = Mockito.mock();
    when(server.registerMBean(bean, name)).thenThrow(throwableType);
    assertThrows(CacheException.class, () -> JmxRegistration.register(server, name, bean));
  }

  @ParameterizedTest
  @ValueSource(classes = {InstanceNotFoundException.class, MBeanRegistrationException.class})
  void unregister_error(Class<? extends Throwable> throwableType) throws JMException {
    var name = new ObjectName("");
    MBeanServer server = Mockito.mock();
    when(server.queryNames(any(), any())).thenReturn(Set.of(name));
    doThrow(throwableType).when(server).unregisterMBean(any());
    assertThrows(CacheException.class, () -> JmxRegistration.unregister(server, name));
  }

  @Test
  void newObjectName_malformed() {
    assertThrows(CacheException.class, () -> JmxRegistration.newObjectName("a=b"));
  }

  @Test
  void sanitize() {
    assertThat(JmxRegistration.sanitize(nullRef())).isEmpty();
    assertThat(JmxRegistration.sanitize("a.b")).isEqualTo("a.b");
    assertThat(JmxRegistration.sanitize("a,b")).isEqualTo("a.b");
    assertThat(JmxRegistration.sanitize("a:b")).isEqualTo("a.b");
    assertThat(JmxRegistration.sanitize("a=b")).isEqualTo("a.b");
    assertThat(JmxRegistration.sanitize("a\nb")).isEqualTo("a.b");
  }
}
