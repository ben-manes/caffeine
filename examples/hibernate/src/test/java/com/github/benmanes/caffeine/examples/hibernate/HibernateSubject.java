/*
 * Copyright 2023 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.examples.hibernate;

import static com.google.common.truth.Truth.assertAbout;

import org.hibernate.SessionFactory;

import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Subject;

/**
 * Propositions for {@link SessionFactory}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class HibernateSubject extends Subject {
  private final SessionFactory actual;

  private HibernateSubject(FailureMetadata metadata, SessionFactory sessionFactory) {
    super(metadata, sessionFactory);
    this.actual = sessionFactory;
  }

  public static HibernateSubject assertThat(SessionFactory actual) {
    return assertAbout(HibernateSubject::new).that(actual);
  }

  public HibernateSubject hits(int hitCount) {
    check("hits").that(actual.getStatistics()
        .getSecondLevelCacheHitCount()).isEqualTo(hitCount);
    return this;
  }

  public HibernateSubject misses(int missCount) {
    check("misses").that(actual.getStatistics()
        .getSecondLevelCacheMissCount()).isEqualTo(missCount);
    return this;
  }

  public HibernateSubject puts(int putCount) {
    check("puts").that(actual.getStatistics()
        .getSecondLevelCachePutCount()).isEqualTo(putCount);
    return this;
  }
}
