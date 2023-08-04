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

import static java.util.Objects.requireNonNull;

import java.util.List;

import org.hibernate.SessionFactory;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Repository {
  private final SessionFactory sessionFactory;

  public Repository(SessionFactory sessionFactory) {
    this.sessionFactory = requireNonNull(sessionFactory);
  }

  public Project getProject(long id) {
    return sessionFactory.fromSession(session -> session.get(Project.class, id));
  }

  public User getUser(long id) {
    return sessionFactory.fromSession(session -> session.get(User.class, id));
  }

  public Project findProject(long id) {
    return sessionFactory.fromSession(session ->
        session.createQuery("FROM Project WHERE id = :id", Project.class)
          .setParameter("id", id)
          .uniqueResult());
  }

  public List<Project> findProjects() {
    return sessionFactory.fromSession(session ->
        session.createQuery("FROM Project", Project.class).list());
  }

  public void updateProject(long id, String name) {
    sessionFactory.inTransaction(session -> {
      var project = session.get(Project.class, id);
      project.setName(name);
      session.merge(project);
    });
  }

  public void persist(User user, Project project, Skill skill) {
    sessionFactory.inTransaction(session -> {
      session.persist(project);
      session.persist(skill);
      session.persist(user);
    });
  }

  public void evictProject(long projectId) {
    sessionFactory.getCache().evictEntityData(Project.class, projectId);
  }

  public void evictAll() {
    sessionFactory.getCache().evictDefaultQueryRegion();
  }
}
