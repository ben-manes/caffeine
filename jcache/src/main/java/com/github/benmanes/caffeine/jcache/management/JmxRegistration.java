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
package com.github.benmanes.caffeine.jcache.management;

import static java.util.Locale.US;

import java.lang.management.ManagementFactory;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

/**
 * Jmx cache utilities.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JmxRegistration {
  public enum MBeanType { Configuration, Statistics }

  private JmxRegistration() {}

  /**
   * Registers the JMX management bean for the cache.
   *
   * @param cache the cache to register
   * @param mxbean the management bean
   * @param type the mxbean type
   */
  public static void registerMXBean(Cache<?, ?> cache, Object mxbean, MBeanType type) {
    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    ObjectName objectName = getObjectName(cache, type);
    register(server, objectName, mxbean);
  }

  /**
   * Unregisters the JMX management bean for the cache.
   *
   * @param cache the cache to unregister
   * @param type the mxbean type
   */
  public static void unregisterMXBean(Cache<?, ?> cache, MBeanType type) {
    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    ObjectName objectName = getObjectName(cache, type);
    unregister(server, objectName);
  }

  /** Registers the management bean with the given object name. */
  static void register(MBeanServer server, ObjectName objectName, Object mbean) {
    try {
      if (!server.isRegistered(objectName)) {
        server.registerMBean(mbean, objectName);
      }
    } catch (InstanceAlreadyExistsException
        | MBeanRegistrationException | NotCompliantMBeanException e) {
      throw new CacheException("Error registering " + objectName, e);
    }
  }

  /** Unregisters the management bean(s) with the given object name. */
  static void unregister(MBeanServer server, ObjectName objectName) {
    try {
      for (ObjectName name : server.queryNames(objectName, null)) {
        server.unregisterMBean(name);
      }
    } catch (MBeanRegistrationException | InstanceNotFoundException e) {
      throw new CacheException("Error unregistering " + objectName, e);
    }
  }

  /** Returns the object name of the management bean. */
  static ObjectName getObjectName(Cache<?, ?> cache, MBeanType type) {
    String cacheManagerName = sanitize(cache.getCacheManager().getURI().toString());
    String cacheName = sanitize(cache.getName());
    String name = String.format(US, "javax.cache:type=Cache%s,CacheManager=%s,Cache=%s",
        type, cacheManagerName, cacheName);
    return newObjectName(name);
  }

  static ObjectName newObjectName(String name) {
    try {
      return new ObjectName(name);
    } catch (MalformedObjectNameException e) {
      String msg = "Illegal ObjectName: " + name;
      throw new CacheException(msg, e);
    }
  }

  /** Returns a sanatized string for use as a management bean name. */
  static String sanitize(String name) {
    return (name == null) ? "" : name.replaceAll("[,:=\n]", ".");
  }
}
