---
name: sim-compare
description: Run cache policy hit rate comparison across multiple cache sizes with charts
argument-hint: "<trace-file> [policies...] [sizes...]"
context: fork
disable-model-invocation: true
allowed-tools: Read, Grep, Glob, Bash
---

Run a comprehensive cache policy comparison for the given trace.

## Input

Trace file: $ARGUMENTS

If no policies or sizes are specified, use sensible defaults based on the trace.

## Workflow

1. **Identify the trace format.** Check the file extension and contents:
   - `.gz` files: try common formats (lirs, arc, etc.)
   - Look at `simulator/src/main/resources/reference.conf` for format options
   - Use `format:path` syntax (e.g., `lirs:trace.gz`)

2. **Select policies to compare.** Policy names use `category.PolicyName` format.
   Note: config categories use hyphens (`two-queue`, `greedy-dual`), not underscores.
   Each policy is paired with configured admission filters (default: Always, TinyLfu,
   Clairvoyant), creating multiple instances per policy name.

   Include at minimum:
   - `product.Caffeine` (the production implementation)
   - `opt.Clairvoyant` (theoretical optimal, upper bound)
   - `opt.Unbounded` (infinite cache, ceiling)
   - `linked.Lru` (baseline)
   - `sketch.WindowTinyLfu` (research W-TinyLFU)
   - `sketch.HillClimberWindowTinyLfu` (adaptive variant)
   - Add relevant competitors based on trace characteristics:
     - For recency-heavy: `linked.S4Lru`, `adaptive.Arc`
     - For frequency-heavy: `linked.Lfu`, `irr.Lirs`
     - For scan-resistant: `two-queue.TwoQueue`, `two-queue.S3Fifo`
     - For size-aware traces: `greedy-dual.Gdsf`, `greedy-dual.Camp`

3. **Choose cache sizes.** Use a geometric progression covering the working set:
   - Start small (e.g., 100), end near working set size
   - 5-8 sizes: e.g., `100,500,1_000,2_500,5_000,10_000,25_000`
   - If the trace has few distinct keys, reduce the range

4. **Run the simulation.** Use the Gradle task:
   ```bash
   ./gradlew simulator:simulate -q \
     --maximumSize=100,500,1000,2500,5000,10000 \
     --metric="Hit Rate" \
     --title="Description" \
     --theme=light \
     --outputDir=build/reports/sim
   ```
   Override the trace and policies via system properties appended to the command:
   ```bash
   -Dcaffeine.simulator.files.paths.0="format:path/to/trace"
   -Dcaffeine.simulator.policies.0=product.Caffeine
   -Dcaffeine.simulator.policies.1=opt.Clairvoyant
   # ... etc
   ```
   Note: for single-size runs, use `./gradlew simulator:run -q` with
   `-Dcaffeine.simulator.maximum-size=N` instead of `simulator:simulate`.

5. **Read and interpret results.** The simulate task produces:
   - Individual CSV per cache size
   - Combined CSV (policies as rows, sizes as columns)
   - PNG chart (line graph of metric vs cache size)
   Read the CSV output files in the output directory:
   - Compare hit rates across policies at each cache size
   - Identify the crossover points where one policy overtakes another
   - Note the gap between Caffeine and Clairvoyant (theoretical ceiling)

6. **Explain findings.** For each notable result:
   - WHY does policy X beat policy Y on this trace?
   - What trace characteristic drives the difference? (frequency bias, recency bias, scan patterns, temporal shifts)
   - How close is Caffeine to optimal? Where does it lose?
   - Reference the relevant research paper if applicable:
     - TinyLFU paper for admission filter behavior
     - Adaptive paper for hill climber effectiveness
     - See `.claude/docs/research-foundations.md` for paper-to-code mapping

7. **Report.** Present:
   - Summary table of hit rates at each cache size
   - Key takeaways (2-3 sentences)
   - Notable policy behaviors
   - Path to generated chart PNG
