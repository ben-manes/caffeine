---
name: sim-analyze
description: Analyze a cache trace file to understand its characteristics and recommend policies
argument-hint: "<trace-file>"
context: fork
disable-model-invocation: true
allowed-tools: Read, Grep, Glob, Bash
---

Analyze the given cache trace to understand its access pattern characteristics
and recommend which cache policies would perform best.

## Input

Trace file: $ARGUMENTS

## Workflow

1. **Identify the trace format** and read the trace:
   - Check file extension, try to parse first lines
   - Determine: key-only or weighted? Timestamps included?
   - Count total accesses and distinct keys

2. **Compute trace statistics.** Write a small analysis script or use
   the simulator's synthetic tools to characterize:
   - **Working set size**: number of distinct keys
   - **Temporal working set**: distinct keys in sliding windows
   - **Frequency distribution**: how many keys account for 80% of accesses?
     (Zipfian? Uniform? Bimodal?)
   - **Recency patterns**: what fraction of accesses are repeats within
     the last N accesses?
   - **Scan detection**: are there bursts of sequential unique keys?
   - **Temporal shifts**: does the popular set change over time?
     (Compare first half vs second half frequency rankings)

3. **Characterize the workload:**
   - **Frequency-biased**: few hot keys dominate → LFU/TinyLFU excel
   - **Recency-biased**: recent items are reaccessed → LRU/LIRS excel
   - **Mixed**: both signals matter → W-TinyLFU, ARC
   - **Scan-heavy**: sequential scans pollute → scan-resistant policies (S3-FIFO, 2Q)
   - **Shifting**: popular set changes over time → adaptive policies (hill climber)

4. **Recommend policies** for comparison:
   - Match trace characteristics to policy strengths
   - Suggest cache sizes based on working set (10%, 25%, 50% of distinct keys)
   - Predict which policies will likely win and why

5. **Run a quick validation.** Execute a single-size simulation using
   `simulator:run` (not `simulator:simulate` which does multi-size):
   ```bash
   ./gradlew simulator:run -q \
     -Dcaffeine.simulator.files.paths.0="format:path" \
     -Dcaffeine.simulator.maximum-size=SIZE \
     -Dcaffeine.simulator.policies.0=product.Caffeine \
     -Dcaffeine.simulator.policies.1=opt.Clairvoyant \
     -Dcaffeine.simulator.policies.2=linked.Lru
   ```
   Note: each policy creates instances per admission filter (default: Always,
   TinyLfu, Clairvoyant). If a trace has no weight data, weighted-only policies
   are silently skipped.

6. **Report:**
   - Trace summary (accesses, distinct keys, frequency distribution shape)
   - Workload characterization (frequency vs recency bias)
   - Recommended policies with reasoning
   - Quick validation results
   - Suggested `/sim-compare` invocation for full analysis
