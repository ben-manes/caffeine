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

import java.util.logging.Level;
import java.util.logging.Logger;

import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class HibernateCacheTest {
  SessionFactory sessionFactory;
  Repository repository;

  @BeforeEach
  public void beforeEach() {
    Logger.getLogger("").setLevel(Level.WARNING);
    sessionFactory = new Configuration().addAnnotatedClass(User.class)
        .addAnnotatedClass(Skill.class).addAnnotatedClass(Project.class)
        .buildSessionFactory(new StandardServiceRegistryBuilder().build());
    repository = new Repository(sessionFactory);
  }

  @AfterEach
  public void afterEach() {
    sessionFactory.close();
  }

  @Test
  public void lookup() {
    long projectId = createData("Ben", "Caffeine", "Hibernate");

    // db hit
    Project project = repository.getProject(projectId);

    // cache hit
    project = repository.getProject(projectId);
    assertThat(sessionFactory).hits(4).misses(0).puts(3);

    // cache hit due to project associations
    repository.getUser(project.getAssignee().getId());
    assertThat(sessionFactory).hits(5).misses(0).puts(3);

    // cache hit due to only the project being explicitly evicted
    repository.evictProject(projectId);
    repository.getUser(project.getAssignee().getId());
    assertThat(sessionFactory).hits(6).misses(0).puts(3);

    // db hit
    project = repository.getProject(projectId);
    assertThat(sessionFactory).hits(6).misses(1).puts(4);
  }

  @Test
  public void query() {
    long projectId = createData("Ben", "Caffeine", "Hibernate");

    // db hit
    repository.findProject(projectId);
    assertThat(sessionFactory).hits(1).misses(0).puts(3);

    // cache hit
    repository.findProject(projectId);
    assertThat(sessionFactory).hits(2).misses(0).puts(3);

    // db hit
    repository.findProjects();
    assertThat(sessionFactory).hits(3).misses(0).puts(3);

    // cache hit
    repository.findProjects();
    assertThat(sessionFactory).hits(4).misses(0).puts(3);

    // db hit
    repository.evictAll();
    repository.findProject(projectId);
    assertThat(sessionFactory).hits(5).misses(0).puts(3);

    // query cache hit
    repository.findProject(projectId);
    assertThat(sessionFactory).hits(6).misses(0).puts(3);

    // db hit
    repository.findProjects();
    assertThat(sessionFactory).hits(7).misses(0).puts(3);

    // cache hit
    repository.findProjects();
    assertThat(sessionFactory).hits(8).misses(0).puts(3);

    // automatically evicts project-related queries in the cache
    repository.updateProject(projectId, "Updated");
    assertThat(sessionFactory).hits(10).misses(0).puts(4);

    // db hit
    repository.findProject(projectId);
    assertThat(sessionFactory).hits(11).misses(0).puts(4);

    // db hit
    repository.findProjects();
    assertThat(sessionFactory).hits(12).misses(0).puts(4);
  }

  private long createData(String userName, String projectName, String skillName) {
    var project = new Project();
    var skill = new Skill();
    var user = new User();

    user.setName(userName);
    user.getSkills().add(skill);
    user.getProjects().add(project);

    project.setName(projectName);
    project.setAssignee(user);

    skill.setName(skillName);

    repository.persist(project, user, skill);
    return project.getId();
  }
}
