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
import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.builder.ToStringStyle.MULTI_LINE_STYLE;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.apache.commons.lang3.builder.ToStringBuilder;

import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.google.auto.value.AutoValue;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import net.autobuilder.AutoBuilder;

/**
 * Statistics gathered by a policy execution. A policy can extend this class as a convenient way to
 * add custom metrics.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class PolicyStats {
  private final Map<String, Metric> metrics;
  private final Stopwatch stopwatch;

  private String name;
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

  @FormatMethod
  public PolicyStats(@FormatString String format, Object... args) {
    this(String.format(format, args));
  }

  public PolicyStats(String name) {
    this.name = requireNonNull(name);
    this.metrics = new LinkedHashMap<>();
    this.stopwatch = Stopwatch.createUnstarted();

    addMetric(Metric.of("Policy", (Supplier<String>) this::name, OBJECT, true));
    addMetric(Metric.of("Hit Rate", (DoubleSupplier) this::hitRate, PERCENT, true));
    addMetric(Metric.of("Hits", (LongSupplier) this::hitCount, NUMBER, true));
    addMetric(Metric.of("Misses", (LongSupplier) this::missCount, NUMBER, true));
    addMetric(Metric.of("Requests", (LongSupplier) this::requestCount, NUMBER, true));
    addMetric(Metric.of("Evictions", (LongSupplier) this::evictionCount, NUMBER, true));
    addPercentMetric("Admit rate",
        () -> (admittedCount + rejectedCount) == 0 ? 0 : admissionRate());
    addMetric(Metric.builder()
        .value((LongSupplier) this::requestsWeight)
        .addToCharacteristics(WEIGHTED)
        .name("Requests Weight")
        .type(NUMBER)
        .build());
    addMetric(Metric.builder()
        .value((DoubleSupplier) this::weightedHitRate)
        .addToCharacteristics(WEIGHTED)
        .name("Weighted Hit Rate")
        .type(PERCENT)
        .build());
    addPercentMetric("Adaption", this::percentAdaption);
    addMetric("Average Miss Penalty", this::averageMissPenalty);
    addMetric("Average Penalty", this::avergePenalty);
    addMetric("Steps", this::operationCount);
    addMetric("Time", this::stopwatch);
  }

  public void addMetric(Metric metric) {
    metrics.put(metric.name(), requireNonNull(metric));
  }

  public void addMetric(String name, Supplier<?> supplier) {
    addMetric(Metric.builder().name(name).value(supplier).type(OBJECT).build());
  }

  public void addMetric(String name, LongSupplier supplier) {
    addMetric(Metric.builder().name(name).value(supplier).type(NUMBER).build());
  }

  public void addMetric(String name, DoubleSupplier supplier) {
    addMetric(Metric.builder().name(name).value(supplier).type(NUMBER).build());
  }

  public void addPercentMetric(String name, DoubleSupplier supplier) {
    addMetric(Metric.builder().name(name).value(supplier).type(PERCENT).build());
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

  public double avergePenalty() {
    long requestCount = requestCount();
    return (requestCount == 0) ? 0.0 : totalPenalty() / requestCount;
  }

  public double avergeHitPenalty() {
    return (hitCount == 0) ? 0.0 : hitPenalty / hitCount;
  }

  public double averageMissPenalty() {
    return (missCount == 0) ? 0.0 : missPenalty / missCount;
  }

  @Override
  public String toString() {
    return ToStringBuilder.reflectionToString(this, MULTI_LINE_STYLE);
  }

  @AutoValue @AutoBuilder
  public static abstract class Metric {
    public enum MetricType { NUMBER, PERCENT, OBJECT }

    public abstract String name();
    public abstract Object value();
    public abstract MetricType type();
    public abstract boolean required();
    public abstract ImmutableSet<Characteristic> characteristics();

    @SuppressWarnings("PMD.ShortMethodName")
    public static Metric of(String name, Object value, MetricType type, boolean required) {
      return builder().name(name).value(value).type(type).required(required).build();
    }
    public static PolicyStats_Metric_Builder builder() {
      return PolicyStats_Metric_Builder.builder();
    }
  }
}
