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

import java.io.Serializable;
import java.util.Comparator;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats.Metric;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats.Metric.MetricType;
import com.google.common.base.MoreObjects;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * A utility for performing common operations against a {@link Metric}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public record Metrics(Function<Object, String> objectFormatter, LongFunction<String> longFormatter,
    DoubleFunction<String> percentFormatter, DoubleFunction<String> doubleFormatter) {

  public Metrics {
    requireNonNull(longFormatter);
    requireNonNull(objectFormatter);
    requireNonNull(doubleFormatter);
    requireNonNull(percentFormatter);
  }

  /** Returns the stringified value for the metric; empty if absent. */
  public String format(Metric metric) {
    if (metric == null) {
      return "";
    } else if (metric.value() instanceof LongSupplier supplier) {
      long value = supplier.getAsLong();
      return (value > 0) || metric.required()
          ? longFormatter().apply(value)
          : "";
    } else if (metric.value() instanceof DoubleSupplier supplier) {
      double value = supplier.getAsDouble();
      if ((value == 0.0) && !metric.required()) {
        return "";
      }
      return (metric.type() == MetricType.PERCENT)
          ? percentFormatter().apply(value)
          : doubleFormatter().apply(value);
    } else if (metric.value() instanceof Supplier<?> supplier) {
      Object value = supplier.get();
      return MoreObjects.firstNonNull(objectFormatter().apply(value), "");
    }
    return MoreObjects.firstNonNull(objectFormatter().apply(metric.value()), "");
  }

  /** A comparator to sort by the given column. */
  public Comparator<PolicyStats> comparator(String header) {
    return new MetricComparator(this, header);
  }

  public static Metrics.Builder builder() {
    return new Metrics.Builder()
        .percentFormatter(value -> (value == 0.0) ? "" : Double.toString(100 * value))
        .doubleFormatter(value -> (value == 0.0) ? "" : Double.toString(value))
        .objectFormatter(object -> (object == null) ? "" : object.toString())
        .longFormatter(value -> (value == 0) ? "" : Long.toString(value));
  }

  private record MetricComparator(Metrics metrics, String header)
      implements Comparator<PolicyStats>, Serializable {
    private MetricComparator {
      requireNonNull(metrics);
      requireNonNull(header);
    }
    @Override public int compare(PolicyStats p1, PolicyStats p2) {
      Metric metric1 = p1.metrics().get(header);
      Metric metric2 = p2.metrics().get(header);
      if (metric1 == null) {
        return (metric2 == null) ? 0 : -1;
      } else if (metric2 == null) {
        return 1;
      } else if (metric1.value() instanceof LongSupplier supplier1
          && metric2.value() instanceof LongSupplier supplier2) {
        return Long.compare(supplier1.getAsLong(), supplier2.getAsLong());
      } else if (metric1.value() instanceof DoubleSupplier supplier1
          && metric2.value() instanceof DoubleSupplier supplier2) {
        return Double.compare(supplier1.getAsDouble(), supplier2.getAsDouble());
      } else if (metric1.value() instanceof Supplier<?> supplier1
          && metric2.value() instanceof Supplier<?> supplier2) {
        Object value1 = supplier1.get();
        Object value2 = supplier2.get();
        if (value1 instanceof Comparable<?>) {
          @SuppressWarnings("unchecked")
          var comparable = (Comparable<Object>) value1;
          return comparable.compareTo(value2);
        }
        return metrics.objectFormatter.apply(value1)
            .compareTo(metrics.objectFormatter.apply(value2));
      }
      return metrics.objectFormatter.apply(metric1.value())
          .compareTo(metrics.objectFormatter.apply(metric2.value()));
    }
  }

  public static final class Builder {
    private @Nullable Function<Object, String> objectFormatter;
    private @Nullable DoubleFunction<String> percentFormatter;
    private @Nullable DoubleFunction<String> doubleFormatter;
    private @Nullable LongFunction<String> longFormatter;

    @CanIgnoreReturnValue
    public Builder objectFormatter(Function<Object, String> objectFormatter) {
      this.objectFormatter = requireNonNull(objectFormatter);
      requireNonNull(objectFormatter);
      return this;
    }
    @CanIgnoreReturnValue
    public Builder percentFormatter(DoubleFunction<String> percentFormatter) {
      this.percentFormatter = requireNonNull(percentFormatter);
      return this;
    }
    @CanIgnoreReturnValue
    public Builder doubleFormatter(DoubleFunction<String> doubleFormatter) {
      this.doubleFormatter = requireNonNull(doubleFormatter);
      return this;
    }
    @CanIgnoreReturnValue
    public Builder longFormatter(LongFunction<String> longFormatter) {
      this.longFormatter = requireNonNull(longFormatter);
      return this;
    }
    public Metrics build() {
      requireNonNull(longFormatter);
      requireNonNull(objectFormatter);
      requireNonNull(doubleFormatter);
      requireNonNull(percentFormatter);
      return new Metrics(objectFormatter, longFormatter, percentFormatter, doubleFormatter);
    }
  }
}
