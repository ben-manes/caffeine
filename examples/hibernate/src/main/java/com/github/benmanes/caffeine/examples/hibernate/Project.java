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

import static org.hibernate.annotations.CacheConcurrencyStrategy.READ_WRITE;

import org.hibernate.annotations.Cache;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Entity
@Cache(usage = READ_WRITE)
public class Project {

  @Id
  @GeneratedValue
  private long id;

  private String name;

  @ManyToOne
  private User assignee;

  public long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public User getAssignee() {
    return assignee;
  }

  public void setAssignee(User assignee) {
    this.assignee = assignee;
  }
}
