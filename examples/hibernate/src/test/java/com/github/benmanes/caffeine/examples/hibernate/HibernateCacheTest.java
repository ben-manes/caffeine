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

import static com.github.benmanes.caffeine.examples.hibernate.HibernateSubject.assertThat;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class HibernateCacheTest {

  @Test
  void lookup() {
    try (var sessionFactory = newSessionFactory()) {
      long projectId = sessionFactory.fromTransaction(session ->
          createData(session, "Ben", "Caffeine", "Hibernate"));

      // db hit
      var project = sessionFactory.fromSession(session -> Queries_.getProject(session, projectId));

      // cache hit
      sessionFactory.inSession(session -> Queries_.getProject(session, projectId));
      assertThat(sessionFactory).hits(4).misses(0).puts(3);

      // cache hit due to project associations
      sessionFactory.inSession(session -> Queries_.getUser(session, project.getAssignee().getId()));
      assertThat(sessionFactory).hits(5).misses(0).puts(3);

      // cache hit due to only the project being explicitly evicted
      sessionFactory.getCache().evict(Project.class, projectId);
      sessionFactory.inSession(session -> Queries_.getUser(session, project.getAssignee().getId()));
      assertThat(sessionFactory).hits(6).misses(0).puts(3);

      // db hit
      sessionFactory.inSession(session -> Queries_.getProject(session, projectId));
      assertThat(sessionFactory).hits(6).misses(1).puts(4);
    }
  }

  @Test
  void query() {
    try (var sessionFactory = newSessionFactory()) {
      long projectId = sessionFactory.fromTransaction(session ->
          createData(session, "Ben", "Caffeine", "Hibernate"));

      // db hit
      sessionFactory.inSession(session -> Queries_.findProject(session, projectId)
          .setCacheable(true).getSingleResult());
      assertThat(sessionFactory).hits(1).misses(0).puts(3);
      assertThat(sessionFactory).queryHits(0).queryMisses(1).queryPuts(1);

      // cache hit
      sessionFactory.inSession(session -> Queries_.findProject(session, projectId)
          .setCacheable(true).getSingleResult());
      assertThat(sessionFactory).hits(2).misses(0).puts(3);
      assertThat(sessionFactory).queryHits(1).queryMisses(1).queryPuts(1);

      // db hit
      sessionFactory.inSession(session -> Queries_.findProjects(session)
          .setCacheable(true).getResultList());
      assertThat(sessionFactory).hits(3).misses(0).puts(3);
      assertThat(sessionFactory).queryHits(1).queryMisses(2).queryPuts(2);

      // cache hit
      sessionFactory.inSession(session -> Queries_.findProjects(session)
          .setCacheable(true).getResultList());
      assertThat(sessionFactory).hits(4).misses(0).puts(3);
      assertThat(sessionFactory).queryHits(2).queryMisses(2).queryPuts(2);

      // db hit
      sessionFactory.getCache().evictDefaultQueryRegion();
      sessionFactory.inSession(session -> Queries_.findProject(session, projectId)
          .setCacheable(true).getSingleResult());
      assertThat(sessionFactory).hits(5).misses(0).puts(3);
      assertThat(sessionFactory).queryHits(2).queryMisses(3).queryPuts(3);

      // query cache hit
      sessionFactory.inSession(session -> Queries_.findProject(session, projectId)
          .setCacheable(true).getSingleResult());
      assertThat(sessionFactory).hits(6).misses(0).puts(3);
      assertThat(sessionFactory).queryHits(3).queryMisses(3).queryPuts(3);

      // db hit
      sessionFactory.inSession(session -> Queries_.findProjects(session)
          .setCacheable(true).getResultList());
      assertThat(sessionFactory).hits(7).misses(0).puts(3);
      assertThat(sessionFactory).queryHits(3).queryMisses(4).queryPuts(4);

      // cache hit
      sessionFactory.inSession(session -> Queries_.findProjects(session)
          .setCacheable(true).getResultList());
      assertThat(sessionFactory).hits(8).misses(0).puts(3);
      assertThat(sessionFactory).queryHits(4).queryMisses(4).queryPuts(4);

      // automatically evicts project-related queries in the cache
      sessionFactory.inTransaction(session -> {
        var project = Queries_.getProject(session, projectId);
        project.setName("Updated");
        session.merge(project);
      });
      assertThat(sessionFactory).hits(10).misses(0).puts(4);

      // db hit
      sessionFactory.inSession(session -> Queries_.findProject(session, projectId)
          .setCacheable(true).getSingleResult());
      assertThat(sessionFactory).hits(11).misses(0).puts(4);
      assertThat(sessionFactory).queryHits(4).queryMisses(5).queryPuts(5);

      // db hit
      sessionFactory.inSession(session -> Queries_.findProjects(session)
          .setCacheable(true).getResultList());
      assertThat(sessionFactory).hits(12).misses(0).puts(4);
      assertThat(sessionFactory).queryHits(4).queryMisses(6).queryPuts(6);
    }
  }

  private static SessionFactory newSessionFactory() {
    return new Configuration().addAnnotatedClass(User.class)
        .addAnnotatedClass(Skill.class).addAnnotatedClass(Project.class)
        .buildSessionFactory(new StandardServiceRegistryBuilder().build());
  }

  private long createData(Session session, String userName, String projectName, String skillName) {
    var project = new Project();
    var skill = new Skill();
    var user = new User();

    user.setName(userName);
    user.getSkills().add(skill);
    user.getProjects().add(project);

    project.setName(projectName);
    project.setAssignee(user);

    skill.setName(skillName);

    session.persist(project);
    session.persist(skill);
    session.persist(user);
    return project.getId();
  }
}
