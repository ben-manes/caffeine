/*
 * Copyright 2020 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.report;

import static java.util.Objects.requireNonNull;

import java.util.Comparator;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats.Metric;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats.Metric.MetricType;
import com.google.auto.value.AutoValue;
import com.google.common.base.MoreObjects;

import net.autobuilder.AutoBuilder;

/**
 * A utility for performing common operations against a {@link Metric}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@AutoValue @AutoBuilder
public abstract class Metrics {
  public abstract Function<Object, String> objectFormatter();
  public abstract DoubleFunction<String> percentFormatter();
  public abstract DoubleFunction<String> doubleFormatter();
  public abstract LongFunction<String> longFormatter();

  /** Returns the stringified value for the metric; empty if absent. */
  public String format(Metric metric) {
    if (metric == null) {
      return "";
    } else if (metric.value() instanceof LongSupplier) {
      long value = ((LongSupplier) metric.value()).getAsLong();
      return (value > 0) || metric.required()
          ? longFormatter().apply(value)
          : "";
    } else if (metric.value() instanceof DoubleSupplier) {
      double value = ((DoubleSupplier) metric.value()).getAsDouble();
      if ((value == 0.0) && !metric.required()) {
        return "";
      }
      return (metric.type() == MetricType.PERCENT)
          ? percentFormatter().apply(value)
          : doubleFormatter().apply(value);
    } else if (metric.value() instanceof Supplier) {
      Object value = ((Supplier<?>) metric.value()).get();
      return MoreObjects.firstNonNull(objectFormatter().apply(value), "");
    }
    return MoreObjects.firstNonNull(objectFormatter().apply(metric.value()), "");
  }

  /** A comparator to sort by the given column. */
  public Comparator<PolicyStats> comparator(String header) {
    return new MetricComparator(header);
  }

  public static Metrics_Builder builder() {
    return Metrics_Builder.builder()
        .percentFormatter(value -> (value == 0.0) ? "" : Double.toString(100 * value))
        .doubleFormatter(value -> (value == 0.0) ? "" : Double.toString(value))
        .objectFormatter(object -> (object == null) ? "" : object.toString())
        .longFormatter(value -> (value == 0) ? "" : Long.toString(value));
  }

  private final class MetricComparator implements Comparator<PolicyStats> {
    private final String header;

    public MetricComparator(String header) {
      this.header = requireNonNull(header);
    }

    @Override
    public int compare(PolicyStats p1, PolicyStats p2) {
      Metric metric1 = p1.metrics().get(header);
      Metric metric2 = p2.metrics().get(header);
      if (metric1 == null) {
        return (metric2 == null) ? 0 : -1;
      } else if (metric2 == null) {
        return 1;
      } else if (metric1.value() instanceof LongSupplier) {
        return Long.compare(((LongSupplier) metric1.value()).getAsLong(),
            ((LongSupplier) metric2.value()).getAsLong());
      } else if (metric1.value() instanceof DoubleSupplier) {
        return Double.compare(((DoubleSupplier) metric1.value()).getAsDouble(),
            ((DoubleSupplier) metric2.value()).getAsDouble());
      } else if (metric1.value() instanceof Supplier) {
        Object value1 = ((Supplier<?>) metric1.value()).get();
        Object value2 = ((Supplier<?>) metric2.value()).get();
        if (value1 instanceof Comparable<?>) {
          @SuppressWarnings("unchecked")
          Comparable<Object> comparator = (Comparable<Object>) value1;
          return comparator.compareTo(value2);
        }
        return objectFormatter().apply(value1)
            .compareTo(objectFormatter().apply(value2));
      }
      return objectFormatter().apply(metric1.value())
          .compareTo(objectFormatter().apply(metric2.value()));
    }
  }
}
