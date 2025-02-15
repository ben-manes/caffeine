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
package com.github.benmanes.caffeine.cache.simulator.policy;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import static com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats.Metric.MetricType.NUMBER;
import static com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats.Metric.MetricType.OBJECT;
import static com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats.Metric.MetricType.PERCENT;
import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.builder.ToStringStyle.MULTI_LINE_STYLE;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * Statistics gathered by a policy execution. A policy can extend this class as a convenient way to
 * add custom metrics.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class PolicyStats {
  private final Map<String, Metric> metrics;
  private final Stopwatch stopwatch;
  private final String name;

  private long hitCount;
  private long missCount;
  private long hitsWeight;
  private long missesWeight;
  private double hitPenalty;
  private double missPenalty;
  private long evictionCount;
  private long admittedCount;
  private long rejectedCount;
  private long operationCount;
  private double percentAdaption;

  @SuppressWarnings({"AnnotateFormatMethod", "this-escape"})
  public PolicyStats(String format, Object... args) {
    this.stopwatch = Stopwatch.createUnstarted();
    this.name = String.format(US, format, args);
    this.metrics = new LinkedHashMap<>();

    addMetric(new Metric.Builder()
        .name("Policy").value(this::name).type(OBJECT).required(true));
    addMetric(new Metric.Builder()
        .name("Hit Rate").value(this::hitRate).type(PERCENT).required(true));
    addMetric(new Metric.Builder()
        .name("Miss Rate").value(this::missRate).type(PERCENT).required(true));
    addMetric(new Metric.Builder()
        .name("Hits").value(this::hitCount).type(NUMBER).required(true));
    addMetric(new Metric.Builder()
        .name("Misses").value(this::missCount).type(NUMBER).required(true));
    addMetric(new Metric.Builder()
        .name("Misses").value(this::missCount).type(NUMBER).required(true));
    addMetric(new Metric.Builder()
        .name("Requests").value(this::requestCount).type(NUMBER).required(true));
    addMetric(new Metric.Builder()
        .name("Evictions").value(this::evictionCount).type(NUMBER).required(true));

    addPercentMetric("Admit rate",
        () -> (admittedCount + rejectedCount) == 0 ? 0 : admissionRate());
    addMetric(new Metric.Builder()
        .name("Requests Weight").value(this::requestsWeight)
        .type(NUMBER).characteristic(WEIGHTED));
    addMetric(new Metric.Builder()
        .name("Weighted Hit Rate").value(this::weightedHitRate)
        .characteristic(WEIGHTED).type(PERCENT));
    addMetric(new Metric.Builder()
        .name("Weighted Miss Rate").value(this::weightedMissRate)
        .type(PERCENT).characteristic(WEIGHTED));
    addPercentMetric("Adaption", this::percentAdaption);
    addMetric("Average Miss Penalty", this::averageMissPenalty);
    addMetric("Average Penalty", this::averagePenalty);
    addMetric("Steps", this::operationCount);
    addMetric("Time", this::stopwatch);
  }

  public void addMetric(Metric.Builder metricBuilder) {
    var metric = metricBuilder.build();
    metrics.put(metric.name(), requireNonNull(metric));
  }

  public void addMetric(String name, Supplier<?> supplier) {
    addMetric(new Metric.Builder().name(name).value(supplier).type(OBJECT));
  }

  public void addMetric(String name, LongSupplier supplier) {
    addMetric(new Metric.Builder().name(name).value(supplier).type(NUMBER));
  }

  public void addMetric(String name, DoubleSupplier supplier) {
    addMetric(new Metric.Builder().name(name).value(supplier).type(NUMBER));
  }

  public void addPercentMetric(String name, DoubleSupplier supplier) {
    addMetric(new Metric.Builder().name(name).value(supplier).type(PERCENT));
  }

  public Map<String, Metric> metrics() {
    return metrics;
  }

  public Stopwatch stopwatch() {
    return stopwatch;
  }

  public String name() {
    return name;
  }

  public void recordOperation() {
    operationCount++;
  }

  public long operationCount() {
    return operationCount;
  }

  public void addOperations(long operations) {
    operationCount += operations;
  }

  public void recordHit() {
    hitCount++;
  }

  public long hitCount() {
    return hitCount;
  }

  public void addHits(long hits) {
    hitCount += hits;
  }

  public void recordWeightedHit(int weight) {
    hitsWeight += weight;
    recordHit();
  }

  public long hitsWeight() {
    return hitsWeight;
  }

  public void recordHitPenalty(double penalty) {
    hitPenalty += penalty;
  }

  public double hitPenalty() {
    return hitPenalty;
  }

  public void recordMiss() {
    missCount++;
  }

  public long missCount() {
    return missCount;
  }

  public void addMisses(long misses) {
    missCount += misses;
  }

  public void recordWeightedMiss(int weight) {
    missesWeight += weight;
    recordMiss();
  }

  public long missesWeight() {
    return missesWeight;
  }

  public void recordMissPenalty(double penalty) {
    missPenalty += penalty;
  }

  public double missPenalty() {
    return missPenalty;
  }

  public long evictionCount() {
    return evictionCount;
  }

  public void recordEviction() {
    evictionCount++;
  }

  public void addEvictions(long evictions) {
    evictionCount += evictions;
  }

  public long requestCount() {
    return hitCount + missCount;
  }

  public long requestsWeight() {
    return hitsWeight + missesWeight;
  }

  public long admissionCount() {
    return admittedCount;
  }

  public void recordAdmission() {
    admittedCount++;
  }

  public long rejectionCount() {
    return rejectedCount;
  }

  public void recordRejection() {
    rejectedCount++;
  }

  public double totalPenalty() {
    return hitPenalty + missPenalty;
  }

  public double percentAdaption() {
    return percentAdaption;
  }

  public void setPercentAdaption(double percentAdaption) {
    this.percentAdaption = (Math.floor(100 * percentAdaption) / 100);
  }

  public double hitRate() {
    long requestCount = requestCount();
    return (requestCount == 0) ? 1.0 : (double) hitCount / requestCount;
  }

  public double weightedHitRate() {
    long requestsWeight = requestsWeight();
    return (requestsWeight == 0) ? 1.0 : (double) hitsWeight / requestsWeight;
  }

  public double missRate() {
    long requestCount = requestCount();
    return (requestCount == 0) ? 0.0 : (double) missCount / requestCount;
  }

  public double weightedMissRate() {
    long requestsWeight = requestsWeight();
    return (requestsWeight == 0) ? 1.0 : (double) missesWeight / requestsWeight;
  }

  public double admissionRate() {
    long candidateCount = admittedCount + rejectedCount;
    return (candidateCount == 0) ? 1.0 : (double) admittedCount / candidateCount;
  }

  public double complexity() {
    long requestCount = requestCount();
    return (requestCount == 0) ? 0.0 : (double) operationCount / requestCount;
  }

  public double averagePenalty() {
    long requestCount = requestCount();
    return (requestCount == 0) ? 0.0 : totalPenalty() / requestCount;
  }

  public double averageHitPenalty() {
    return (hitCount == 0) ? 0.0 : hitPenalty / hitCount;
  }

  public double averageMissPenalty() {
    return (missCount == 0) ? 0.0 : missPenalty / missCount;
  }

  @Override
  public String toString() {
    return ToStringBuilder.reflectionToString(this, MULTI_LINE_STYLE);
  }

  public record Metric(String name, Object value, MetricType type,
      ImmutableSet<Characteristic> characteristics, boolean required) {
    public enum MetricType { NUMBER, PERCENT, OBJECT }

    public Metric {
      requireNonNull(type);
      requireNonNull(name);
      requireNonNull(value);
      requireNonNull(characteristics);
    }

    public static final class Builder {
      private final Set<Characteristic> characteristics;

      private @Nullable MetricType type;
      private @Nullable Object value;
      private @Nullable String name;
      private boolean required;

      public Builder() {
        characteristics = EnumSet.noneOf(Characteristic.class);
      }
      @CanIgnoreReturnValue
      public Builder characteristic(Characteristic characteristic) {
        characteristics.add(characteristic);
        return this;
      }
      @CanIgnoreReturnValue
      public Builder name(String name) {
        this.name = requireNonNull(name);
        return this;
      }
      @CanIgnoreReturnValue
      public Builder value(Object value) {
        this.value = requireNonNull(value);
        return this;
      }
      @CanIgnoreReturnValue
      public Builder value(Supplier<?> value) {
        return value((Object) value);
      }
      @CanIgnoreReturnValue
      public Builder value(LongSupplier value) {
        return value((Object) value);
      }
      @CanIgnoreReturnValue
      public Builder value(DoubleSupplier value) {
        return value((Object) value);
      }
      @CanIgnoreReturnValue
      public Builder type(MetricType type) {
        this.type = requireNonNull(type);
        return this;
      }
      @CanIgnoreReturnValue
      public Builder required(boolean required) {
        this.required = required;
        return this;
      }
      public Metric build() {
        requireNonNull(type);
        requireNonNull(name);
        requireNonNull(value);
        return new Metric(name, value, type, Sets.immutableEnumSet(characteristics), required);
      }
    }
  }
}
