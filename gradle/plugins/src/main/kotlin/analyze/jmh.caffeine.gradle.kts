/** Java microbenchmark harness: https://github.com/melix/jmh-gradle-plugin */
@file:Suppress("PackageDirectoryMismatch", "UnstableApiUsage")
import javax.inject.Inject
import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway
import org.gradle.internal.os.OperatingSystem
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.plugins.ide.eclipse.model.Library
import org.gradle.process.ExecOperations
import me.champeau.jmh.JMHTask as JmhTask
import org.gradle.plugins.ide.eclipse.model.Classpath as EclipseClasspath

plugins {
  idea
  eclipse
  id("me.champeau.jmh")
  id("io.morethan.jmhreport")
  id("java-library.caffeine")
}

val asyncProfiler by configurations.registering
val asyncProfilerDir = layout.buildDirectory.dir("reports/jmh/async")
val asyncProfilerExtractionDir = layout.buildDirectory.dir("async-profiler")
val asyncProfilerLibFile = layout.buildDirectory.file("async-profiler-libPath.txt")

configurations.jmh {
  extendsFrom(configurations["testImplementation"])
  exclude(module = libs.slf4j.test.get().name)
  exclude(module = libs.jazzer.get().name)

  resolutionStrategy {
    if (java.toolchain.languageVersion.get().asInt() < 17) {
      force("${libs.coherence.get().module}:22.06.15")
      force("${libs.hazelcast.get().module}:5.3.8")
    }
  }
}

dependencies {
  jmh(libs.bundles.slf4j.nop)
  jmh(platform(libs.jackson.bom))

  asyncProfiler(libs.ap.loader)
}

val extractAsyncProfilerLib = tasks.register<ExtractAsyncProfilerLib>("extractAsyncProfilerLib") {
  group = "Benchmarks"
  description = "Extracts the async-profiler native library via ap-loader"
  libPathFile = asyncProfilerLibFile
  loaderClasspath.from(asyncProfiler)
  extractionDir = asyncProfilerExtractionDir
  javaLauncher = javaToolchains.launcherFor {
    vendor = java.toolchain.vendor
    implementation = java.toolchain.implementation
    languageVersion = java.toolchain.languageVersion
    nativeImageCapable = java.toolchain.nativeImageCapable
  }
  onlyIf { !OperatingSystem.current().isWindows }
}

jmh {
  jmhVersion = libs.versions.jmh.asProvider()

  benchmarkMode.add("thrpt")
  warmupIterations = 3
  iterations = 3
  timeUnit = "s"
  zip64 = true

  jvmArgs = defaultJvmArgs()
  failOnError = true
  forceGC = true
  fork = 1

  resultsFile = layout.buildDirectory.file("reports/jmh/results.json")
  resultFormat = "json"

  val includePattern: String? by project
  includePattern?.let {
    includes = listOf(it)
  }

  // Benchmark parameters: Separated by '&' for parameter types, and ',' for multiple values
  benchmarkParameters = providers.gradleProperty("benchmarkParameters").flatMap { params ->
    val parameters = objects.mapProperty<String, ListProperty<String>>()
    parameters.value(params.split("&").associate { token ->
      val param = token.split("=")
      require(param.size == 2) { "Expected two parts but was ${param.size} in $token" }
      param[0] to objects.listProperty<String>().value(param[1].split(","))
    })
  }.orElse(emptyMap())

  javaLauncher = javaToolchains.launcherFor {
    vendor = java.toolchain.vendor
    implementation = java.toolchain.implementation
    languageVersion = java.toolchain.languageVersion
    nativeImageCapable = java.toolchain.nativeImageCapable
  }

  val asyncProperty = providers.gradleProperty("async")
  if (asyncProperty.isPresent) {
    require(!OperatingSystem.current().isWindows) {
      "async-profiler is not supported on Windows"
    }
    val format = asyncProperty.get().ifBlank { "flamegraph" }
    require(format in setOf("tree", "jfr", "collapsed", "text", "flamegraph")) {
      "async-profiler output must be one of: tree, jfr, collapsed, text, flamegraph (got '$format')"
    }
    val crossPlatformEvents = setOf("cpu", "alloc", "wall", "lock")
    val event = providers.gradleProperty("asyncEvent").orElse("cpu").get().trim()
    require(event.isNotEmpty()) { "asyncEvent must not be blank" }
    if (!OperatingSystem.current().isLinux && event !in crossPlatformEvents) {
      error("async-profiler event '$event' is Linux-only (cross-platform: $crossPlatformEvents)")
    }
    profilers.add(asyncProfilerLibFile.map { file ->
      val libPath = file.asFile.readText().trim()
      val dir = asyncProfilerDir.get().asFile.absolutePath
      "async:libPath=$libPath;event=$event;output=$format;dir=$dir/$event"
    })
  }
}

jmhReport {
  jmhResultPath = file(jmh.resultsFile).toString()
  jmhReportOutput = file(layout.buildDirectory.file("reports/jmh")).toString()
}

// Use --rerun for repeated executions
tasks.withType<JmhTask>().configureEach {
  group = "Benchmarks"
  description = "Executes a Java microbenchmark"
  incompatibleWithConfigurationCache()

  if (providers.gradleProperty("async").isPresent) {
    dependsOn(extractAsyncProfilerLib)
  }

  inputs.property("javaDistribution", javaDistribution()).optional(true)
  inputs.property("benchmarkParameters", jmh.benchmarkParameters)
  inputs.property("includes", includes)
  outputs.file(jmh.resultsFile)
  outputs.cacheIf { true }

  val includePattern = providers.gradleProperty("includePattern")
  doFirst {
    require(includePattern.isPresent) { "jmh: includePattern expected" }
  }
  finalizedBy(tasks.named("jmhReport"))
}

tasks.named<JavaCompile>("jmhCompileGeneratedClasses").configure {
  options.errorprone.enabled = false
}

tasks.named<JavaCompile>("compileJmhJava").configure {
  options.errorprone.nullaway {
    externalInitAnnotations.add("org.openjdk.jmh.annotations.State")
  }
}

tasks.named("jmhJar").configure {
  outputs.cacheIf { true }
}

tasks.named("jmhReport").configure {
  incompatibleWithConfigurationCache()
}

idea.module {
  scopes["PROVIDED"]!!["plus"]!!.add(configurations["jmh"])
}

eclipse.classpath.file.whenMerged {
  if (this is EclipseClasspath) {
    entries.removeIf { (it is Library) && (it.moduleVersion?.name == "slf4j-nop") }
  }
}

@CacheableTask
abstract class ExtractAsyncProfilerLib : DefaultTask() {
  @get:Classpath
  abstract val loaderClasspath: ConfigurableFileCollection
  @get:Nested
  abstract val javaLauncher: Property<JavaLauncher>
  @get:OutputDirectory
  abstract val extractionDir: DirectoryProperty
  @get:OutputFile
  abstract val libPathFile: RegularFileProperty
  @get:Inject
  abstract val execOperations: ExecOperations

  @TaskAction
  fun extract() {
    val out = libPathFile.get().asFile
    out.parentFile.mkdirs()
    out.outputStream().buffered().use { outputStream ->
      execOperations.javaexec {
        systemProperty("ap_loader_extraction_dir", extractionDir.get().asFile.absolutePath)
        executable = javaLauncher.get().executablePath.asFile.absolutePath
        mainClass = "one.profiler.AsyncProfilerLoader"
        standardOutput = outputStream
        classpath(loaderClasspath)
        args("agentpath")
      }
    }
  }
}
