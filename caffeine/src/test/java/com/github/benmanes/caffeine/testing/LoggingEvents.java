/*
 * Copyright 2024 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.testing;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;
import org.slf4j.event.Level;

import com.github.valfirst.slf4jtest.LoggingEvent;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import com.google.common.collect.ForwardingList;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * A fluent, immutable collection of logging events.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.LooseCoupling")
public final class LoggingEvents extends ForwardingList<LoggingEvent> {
  private final List<Predicate<LoggingEvent>> predicates;
  private final ImmutableList<LoggingEvent> events;

  private @Nullable ImmutableList<LoggingEvent> filteredEvents;

  private boolean exclusive;

  private LoggingEvents(Iterable<LoggingEvent> events) {
    this.events = ImmutableList.copyOf(events);
    this.predicates = new ArrayList<>();
  }

  /** Returns the logging events. */
  public static LoggingEvents logEvents() {
    return new LoggingEvents(TestLoggerFactory.getLoggingEvents());
  }

  /** Returns the events with the message. */
  @CanIgnoreReturnValue
  public LoggingEvents withMessage(String formattedMessage) {
    return filter(e -> Objects.equals(e.getFormattedMessage(), formattedMessage));
  }

  /** Returns the events with the message. */
  @CanIgnoreReturnValue
  public LoggingEvents withMessage(Predicate<String> predicate) {
    return filter(e -> predicate.test(e.getFormattedMessage()));
  }

  /** Returns the events without a throwable. */
  @CanIgnoreReturnValue
  public LoggingEvents withoutThrowable() {
    return filter(e -> e.getThrowable().isEmpty());
  }

  /** Returns the events with the same throwable. */
  @CanIgnoreReturnValue
  public LoggingEvents withThrowable(Throwable t) {
    return filter(e -> e.getThrowable().orElse(null) == t);
  }

  /** Returns the events with the throwable class. */
  @CanIgnoreReturnValue
  public LoggingEvents withThrowable(Class<? extends Throwable> clazz) {
    return filter(e -> clazz.isInstance(e.getThrowable().orElse(null)));
  }

  /** Returns the events with a throwable whose cause is the same. */
  @CanIgnoreReturnValue
  public LoggingEvents withUnderlyingCause(Throwable t) {
    return filter(e -> e.getThrowable().filter(error -> error.getCause() == t).isPresent());
  }

  /** Returns the events with a throwable whose cause is an instance of the class. */
  @CanIgnoreReturnValue
  public LoggingEvents withUnderlyingCause(Class<? extends Throwable> clazz) {
    return filter(e -> clazz.isInstance(e.getThrowable().map(Throwable::getCause).orElse(null)));
  }

  /** Returns the events with the log level. */
  @CanIgnoreReturnValue
  public LoggingEvents withLevel(Level level) {
    return filter(e -> e.getLevel() == level);
  }

  /** Returns the events that satisfy the predicate. */
  @CanIgnoreReturnValue
  public LoggingEvents filter(Predicate<LoggingEvent> predicate) {
    predicates.add(requireNonNull(predicate));
    checkState(filteredEvents == null);
    return this;
  }

  /** Returns the events and fails at evaluation time if any would be filtered out. */
  @CanIgnoreReturnValue
  public LoggingEvents exclusively() {
    checkState(filteredEvents == null);
    exclusive = true;
    return this;
  }

  @Override
  protected ImmutableList<LoggingEvent> delegate() {
    if (filteredEvents == null) {
      filteredEvents = applyFilters();
    }
    return filteredEvents;
  }

  /** Returns the events that match the predicates. */
  private ImmutableList<LoggingEvent> applyFilters() {
    var stream = events.stream();
    for (var predicate : predicates) {
      stream = stream.filter(predicate);
    }
    var results = stream.collect(toImmutableList());
    if (exclusive) {
      assertThat(results).hasSize(events.size());
    }
    return results;
  }
}
