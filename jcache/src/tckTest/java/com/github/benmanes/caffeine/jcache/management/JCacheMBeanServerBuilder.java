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

import static java.util.Objects.requireNonNull;

import javax.management.ListenerNotFoundException;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerBuilder;
import javax.management.MBeanServerDelegate;
import javax.management.Notification;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;

/**
 * An MBeanServer for the TCK that sets the mbean server id to the value of the
 * <code>org.jsr107.tck.management.agentId</code> system property.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JCacheMBeanServerBuilder extends MBeanServerBuilder {

  @Override
  public MBeanServer newMBeanServer(String defaultDomain,
      MBeanServer outer, MBeanServerDelegate delegate) {
    MBeanServerDelegate jcacheDelegate = new JCacheMBeanServerDelegate(delegate);
    return new MBeanServerBuilder().newMBeanServer(defaultDomain, outer, jcacheDelegate);
  }

  private static final class JCacheMBeanServerDelegate extends MBeanServerDelegate {
    private final MBeanServerDelegate delegate;

    JCacheMBeanServerDelegate(MBeanServerDelegate delegate) {
      this.delegate = requireNonNull(delegate);
    }

    @Override
    public String getSpecificationName() {
      return delegate.getSpecificationName();
    }

    @Override
    public String getSpecificationVersion() {
      return delegate.getSpecificationVersion();
    }

    @Override
    public String getSpecificationVendor() {
      return delegate.getSpecificationVendor();
    }

    @Override
    public String getImplementationName() {
      return delegate.getImplementationName();
    }

    @Override
    public String getImplementationVersion() {
      return delegate.getImplementationVersion();
    }

    @Override
    public String getImplementationVendor() {
      return delegate.getImplementationVendor();
    }

    @Override
    public MBeanNotificationInfo[] getNotificationInfo() {
      return delegate.getNotificationInfo();
    }

    @Override
    public synchronized void addNotificationListener(NotificationListener listener,
        NotificationFilter filter, Object handback) {
      delegate.addNotificationListener(listener, filter, handback);
    }

    @Override
    public synchronized void removeNotificationListener(NotificationListener listener,
        NotificationFilter filter, Object handback) throws ListenerNotFoundException {
      delegate.removeNotificationListener(listener, filter, handback);
    }

    @Override
    public synchronized void removeNotificationListener(NotificationListener listener)
        throws ListenerNotFoundException {
      delegate.removeNotificationListener(listener);
    }

    @Override
    public void sendNotification(Notification notification) {
      delegate.sendNotification(notification);
    }

    @Override
    public synchronized String getMBeanServerId() {
      return System.getProperty("org.jsr107.tck.management.agentId");
    }
  }
}
