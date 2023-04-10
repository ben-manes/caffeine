/** Cache simulator using tracing data and a family of eviction policy options. */
import org.gradle.plugins.ide.eclipse.model.Classpath as EclipseClasspath
import org.gradle.plugins.ide.eclipse.model.SourceFolder
import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway

plugins {
  id("application")
  id("auto-value-caffeine-conventions")
  id("java-library-caffeine-conventions")
}

dependencies {
  implementation(project(":caffeine"))

  implementation(libs.xz)
  implementation(libs.ohc)
  implementation(libs.ycsb) {
    isTransitive = false
  }
  implementation(libs.zstd)
  implementation(libs.guava)
  implementation(libs.config)
  implementation(libs.stream)
  implementation(libs.tcache)
  implementation(libs.cache2k)
  implementation(libs.picocli)
  implementation(libs.ehcache3)
  implementation(libs.fastutil)
  implementation(libs.hazelcast)
  implementation(libs.jfreechart)
  implementation(libs.commons.io)
  implementation(libs.fast.filter)
  implementation(libs.flip.tables)
  implementation(libs.expiring.map)
  implementation(libs.commons.lang3)
  implementation(libs.commons.math3)
  implementation(libs.commons.compress)
  implementation(libs.bundles.coherence)
  implementation(libs.bundles.slf4j.jdk)
  implementation(libs.univocity.parsers)
}

application {
  mainClass.set("com.github.benmanes.caffeine.cache.simulator.Simulator")
}

forbiddenApis {
  bundledSignatures.addAll(listOf("commons-io-unsafe-2.11.0", "jdk-deprecated",
    "jdk-internal", "jdk-non-portable", "jdk-reflection", "jdk-unsafe"))
}

tasks.withType<JavaCompile>().configureEach {
  options.errorprone.nullaway.disable()
  modularity.inferModulePath.set(true)
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}

tasks.withType<Jar>().configureEach {
  applyOsgi(this, mapOf(
    "Bundle-SymbolicName" to "com.github.benmanes.caffeine.simulator",
    "Automatic-Module-Name" to "com.github.benmanes.caffeine.simulator"))
}

tasks.withType<Javadoc>().configureEach {
  javadocOptions {
    addStringOption("Xdoclint:none", "-quiet")
  }
}

tasks.named<JavaExec>("run").configure {
  systemProperties(caffeineSystemProperties())
  jvmArgs(javaExecJvmArgs())
}

eclipse.classpath.file.beforeMerged {
  if (this is EclipseClasspath) {
    entries.add(SourceFolder(
      relativePath("$buildDir/generated/sources/annotationProcessor/java/main"), "bin/main"))
  }
}

abstract class Simulate @Inject constructor(@Internal val external: ExecOperations,
                                            @Internal val layout: ProjectLayout) : DefaultTask() {
  @get:Input @get:Optional @get:Option(option = "maximumSize", description = "The maximum sizes")
  abstract val maximumSizes: Property<String>
  @get:Input @get:Optional @get:Option(option = "jvmArgs", description = "The jvm arguments")
  abstract val jvmOptions: Property<String>
  @Input @Option(option = "metric", description = "The metric to compare")
  var metric = "Hit Rate"
  @Input @Option(option = "theme", description = "The chart theme")
  var theme = "light"
  @Input @Option(option = "title", description = "The chart title")
  var title = ""
  @get:Input
  abstract val systemProperties: MapProperty<String, Any>
  @get:Input
  abstract val defaultJvmArgs: ListProperty<String>
  @get:InputFiles @get:Classpath
  abstract val classpath: Property<FileCollection>
  @get:OutputDirectory
  val reportDir = File(layout.buildDirectory.get().asFile, "/reports/$name")

  @TaskAction
  fun run() {
    if (!maximumSizes.isPresent) {
      fun yellow(param: String) = "\u001b[33m${param}\u001b[0m"
      fun italic(param: String) = "\u001b[3m${param}\u001b[0m"
      logger.error("\t${yellow("--maximumSize=")}${italic("<Long>[,<Long>...]")} is required")
      return
    }

    val baseName = metric.lowercase().replace(" ", "_")
    val reports = maximumSizes.get().split(",")
      .map { it.replace("_", "").toLong() }
      .sorted()
      .associateWith { simulate(baseName, it) }
    if (reports.size == 1) {
      println("Did not generate a chart as only one data point")
      println("Wrote combined report to " + reports.values.first())
      return
    }
    val combinedReport = combineReports(baseName, reports)
    generateChart(baseName, combinedReport)
  }

  /** Runs the simulation for the given maximumSize and returns the csv report */
  private fun simulate(baseName: String, size: Long): File {
    println(String.format("Running with maximumSize=%,d...", size))
    val report = "$reportDir/${baseName}_$size.csv"
    external.javaexec {
      mainClass.set("com.github.benmanes.caffeine.cache.simulator.Simulator")
      systemProperties(this@Simulate.systemProperties.get())
      classpath(this@Simulate.classpath)
      jvmArgs(defaultJvmArgs.get())
      if (jvmOptions.isPresent) {
        jvmArgs(jvmOptions.get().split(","))
      }
      systemProperties(
        "caffeine.simulator.report.format" to "csv",
        "caffeine.simulator.report.output" to report,
        "caffeine.simulator.maximum-size" to size
      )
    }
    return File(report)
  }

  /** Returns a combined report from the individual runs. */
  private fun combineReports(baseName: String, reports: Map<Long, File>): File {
    val combinedReport = "$reportDir/$baseName.csv"
    external.javaexec {
      mainClass.set("com.github.benmanes.caffeine.cache.simulator.report.csv.CombinedCsvReport")
      classpath(this@Simulate.classpath)
      reports.forEach { (maximumSize, report) ->
        args("--inputFiles", "$maximumSize=$report")
      }
      args("--outputFile", combinedReport)
      args("--metric", metric)
    }
    return File(combinedReport)
  }

  /** Renders a chart from the combined report. */
  private fun generateChart(baseName: String, combinedReport: File) {
    val chart = "$reportDir/$baseName.png"
    external.javaexec {
      mainClass.set("com.github.benmanes.caffeine.cache.simulator.report.csv.PlotCsv")
      classpath(this@Simulate.classpath)
      args("--inputFile", combinedReport)
      args("--outputFile", chart)
      args("--metric", metric)
      args("--title", title)
      args("--theme", theme)
    }
  }
}
tasks.register<Simulate>("simulate") {
  group = "Application"
  description = "Runs multiple simulations and generates an aggregate report"
  dependsOn(tasks.processResources, tasks.compileJava)

  systemProperties.set(caffeineSystemProperties())
  classpath.set(sourceSets["main"].runtimeClasspath)
  defaultJvmArgs.set(javaExecJvmArgs())
  outputs.upToDateWhen { false }
}

abstract class Rewrite @Inject constructor(@Internal val external: ExecOperations) : DefaultTask() {
  @Input @Optional @Option(option = "inputFiles", description = "The trace input files")
  var inputFiles: List<String> = emptyList()
  @get:Input @get:Optional @get:Option(option = "inputFormat", description = "The input format")
  abstract val inputFormat: Property<String>
  @get:Input @get:Optional @get:Option(option = "outputFile", description = "The output file")
  abstract val outputFile: Property<String>
  @get:Input @get:Optional @get:Option(option = "outputFormat", description = "The output format")
  abstract val outputFormat: Property<String>
  @get:InputFiles @get:Classpath
  abstract val classpath: Property<FileCollection>

  @TaskAction
  fun run() {
    external.javaexec {
      classpath(this@Rewrite.classpath)
      mainClass.set("com.github.benmanes.caffeine.cache.simulator.parser.Rewriter")
      if (inputFiles.isNotEmpty()) {
        args("--inputFiles", inputFiles.joinToString(","))
      }
      if (inputFormat.isPresent) {
        args("--inputFormat", inputFormat.get())
      }
      if (outputFile.isPresent) {
        args("--outputFile", outputFile.get())
      }
      if (outputFormat.isPresent) {
        args("--outputFormat", outputFormat.get())
      }
      if (inputFiles.isEmpty() && !inputFormat.isPresent
          && !outputFile.isPresent && !outputFormat.isPresent) {
        args("--help")
      }
    }
  }
}
tasks.register<Rewrite>("rewrite") {
  group = "Application"
  description = "Rewrite traces into the format used by other simulators"
  dependsOn(tasks.processResources, tasks.compileJava)
  classpath.set(sourceSets["main"].runtimeClasspath)
  outputs.upToDateWhen { false }
}
