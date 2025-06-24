import com.google.common.base.CaseFormat
import com.google.common.collect.Sets
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import kotlin.math.max
import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway
import org.gradle.plugins.ide.eclipse.model.Classpath as EclipseClasspath
import org.gradle.plugins.ide.eclipse.model.Library

plugins {
  id("jmh.caffeine")
  id("jcstress.caffeine")
  id("java-library.caffeine")
}

sourceSets {
  create("javaPoet") {
    java.srcDir("src/javaPoet/java")
  }
  create("codeGen") {
    java.srcDir(layout.buildDirectory.dir("generated/sources/node"))
    java.srcDir(layout.buildDirectory.dir("generated/sources/local-cache"))
  }
}

val compileJava by tasks.existing
val jar by tasks.existing(Jar::class)
val compileJavaPoetJava by tasks.existing
val jammAgent by configurations.registering
val collections4Sources: Configuration by configurations.creating
val javaPoetImplementation by configurations.existing
val javaPoetRuntimeOnly by configurations.existing

dependencies {
  api(libs.jspecify)
  api(libs.errorprone.annotations)

  testImplementation(libs.ycsb) {
    isTransitive = false
  }
  testImplementation(libs.jazzer)
  testImplementation(libs.jctools)
  testImplementation(libs.mockito)
  testImplementation(libs.picocli)
  testImplementation(libs.lincheck)
  testImplementation(libs.commons.lang3)
  testImplementation(libs.guava.testlib)
  testImplementation(libs.bundles.awaitility)
  testImplementation(libs.bundles.slf4j.test)
  testImplementation(libs.commons.collections4)
  testImplementation(libs.commons.collections4) {
    artifact { classifier = "tests" }
  }
  testImplementation(sourceSets["codeGen"].output)
  testImplementation(libs.bundles.osgi.test.compile)
  testImplementation(libs.eclipse.collections.testutils)

  testRuntimeOnly(libs.bundles.osgi.test.runtime)

  collections4Sources(libs.commons.collections4) {
    artifact { classifier = "test-sources" }
  }

  jammAgent(libs.jamm) {
    isTransitive = false
  }

  jmh(libs.jamm)
  jmh(libs.tcache)
  jmh(libs.cache2k)
  jmh(libs.ehcache3)
  jmh(libs.hazelcast)
  jmh(libs.jackrabbit)
  jmh(libs.flip.tables)
  jmh(libs.expiring.map)
  jmh(libs.bundles.coherence)
  jmh(libs.concurrentlinkedhashmap)

  javaPoetImplementation(libs.guava)
  javaPoetImplementation(libs.javapoet)
  javaPoetImplementation(libs.jspecify)
  javaPoetImplementation(libs.commons.lang3)

  javaPoetRuntimeOnly(libs.google.java.format)
}

configurations.all {
  resolutionStrategy {
    if (java.toolchain.languageVersion.get().asInt() < 17) {
      force("${libs.eclipse.collections.testutils.get().module}:12.0.0")
    }
  }
}

val compileCodeGenJava by tasks.existing(JavaCompile::class) {
  classpath = sourceSets["main"].runtimeClasspath + sourceSets["main"].output
  inputs.files(compileJava.map { it.outputs.files })

  options.apply {
    compilerArgs.remove("-parameters")
    isDebug = false

    errorprone {
      disable("FieldMissingNullable")
      disable("MissingOverride")
      disable("MemberName")
      disable("Varifier")
      disable("Var")
      nullaway.disable()
    }
  }
}

val generateLocalCaches by tasks.registering(JavaExec::class) {
  codeGenerationTask("LocalCache")
}

val generateNodes by tasks.registering(JavaExec::class) {
  codeGenerationTask("Node")
}

fun JavaExec.codeGenerationTask(generator: String) {
  val directory = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_HYPHEN, generator)
  mainClass = "com.github.benmanes.caffeine.cache.${generator}FactoryGenerator"
  val outputDir = layout.buildDirectory.dir("generated/sources/$directory")
  inputs.files(compileJavaPoetJava.map { it.outputs.files })
  classpath = sourceSets["javaPoet"].runtimeClasspath
  outputs.dir(outputDir)
  outputs.cacheIf { true }
  doFirst {
    args(outputDir.get().asFile.absolutePath)
  }
}

tasks.named<JavaCompile>("compileJava").configure {
  inputs.files(tasks.named<JavaExec>("generateLocalCaches").map { it.outputs.files })
  inputs.files(tasks.named<JavaExec>("generateNodes").map { it.outputs.files })
  finalizedBy(compileCodeGenJava)
  options.apply {
    compilerArgs.addAll(listOf("-Xlint:-auxiliaryclass", "-Xlint:-exports"))
    errorprone {
      disable("CheckReturnValue")
    }
  }
}

tasks.named<JavaCompile>("compileTestJava").configure {
  inputs.files(compileCodeGenJava.map { it.outputs.files })
  inputs.files(jar.map { it.outputs.files })
  options.apply {
    compilerArgs.add("-Xlint:-auxiliaryclass")
    errorprone {
      disable("Varifier")
      disable("Var")
    }
  }
}

tasks.named<JavaCompile>("compileJmhJava").configure {
  options.apply {
    compilerArgs.add("-Xlint:-classfile")
    errorprone {
      disable("Varifier")
      disable("Var")
    }
  }
}

val standaloneTest = tasks.register<Test>("standaloneTest") {
  group = "Verification"
  description = "Tests that are not part of an explicit suite"
  exclude("com/github/benmanes/caffeine/cache/**")
  useTestNG {
    threadCount = max(6, Runtime.getRuntime().availableProcessors() - 1)
    excludeGroups("slow", "isolated", "lincheck")
    parallel = "methods"
  }
}

val isolatedTest = tasks.register<Test>("isolatedTest") {
  group = "Verification"
  description = "Tests that must be run in isolation"
  maxHeapSize = "3g"
  useTestNG {
    includeGroups("isolated")
  }
}

val lincheckTest = tasks.register<Test>("lincheckTest") {
  group = "Verification"
  description = "Tests that assert linearizability"
  val isEnabled = isEarlyAccess().map { !it }
  testLogging.events("started")
  onlyIf { isEnabled.get() }
  maxHeapSize = "3g"
  failFast = true
  useTestNG {
    includeGroups("lincheck")
  }
}

val fuzzTest = tasks.register<Test>("fuzzTest") {
  group = "Verification"
  description = "Fuzz tests"
  include("com/github/benmanes/caffeine/fuzz/**")
  environment("JAZZER_FUZZ", "1")
  useJUnitPlatform()
  failFast = true
  forkEvery = 1
}

val junitJupiterTest = tasks.register<Test>("junitJupiterTest") {
  group = "Verification"
  description = "JUnit Jupiter tests"
  exclude("com/github/benmanes/caffeine/fuzz/**")
  useJUnitPlatform()
  systemProperties(
    "junit.jupiter.execution.parallel.enabled" to "true",
    "junit.jupiter.execution.parallel.mode.default" to "concurrent")
}

val junitTest = tasks.register<Test>("junitTest") {
  group = "Verification"
  description = "JUnit classic tests"
  inputs.files(jar.map { it.outputs.files })
  maxParallelForks = Runtime.getRuntime().availableProcessors()
  systemProperty("caffeine.osgi.jar", relativePath(jar.get().archiveFile.get().asFile.path))
  useJUnit()
}

tasks.named<Test>("test").configure {
  exclude("com/github/benmanes/caffeine/**")
  dependsOn(junitJupiterTest)
  dependsOn(standaloneTest)
  dependsOn(isolatedTest)
  dependsOn(lincheckTest)
  dependsOn(junitTest)
  dependsOn(fuzzTest)
}

tasks.named<Jar>("jar").configure {
  from(sourceSets["main"].output + sourceSets["codeGen"].output)
  inputs.files(compileCodeGenJava.map { it.outputs.files })
  bundle.bnd(mapOf(
    "Bundle-SymbolicName" to "com.github.ben-manes.caffeine",
    "Import-Package" to "",
    "Export-Package" to listOf(
      "com.github.benmanes.caffeine",
      "com.github.benmanes.caffeine.cache",
      "com.github.benmanes.caffeine.cache.stats").joinToString(","),
    "Automatic-Module-Name" to "com.github.benmanes.caffeine"))
}

tasks.named<Jar>("sourcesJar").configure {
  inputs.files(generateLocalCaches.map { it.outputs.files })
  inputs.files(generateNodes.map { it.outputs.files })
  from(sourceSets["codeGen"].allSource)
}

tasks.named<Javadoc>("javadoc").configure {
  mustRunAfter(compileCodeGenJava)
}

tasks.withType<Test>().configureEach {
  testClassesDirs = files(sourceSets["test"].output.classesDirs)
  classpath = files(sourceSets["test"].runtimeClasspath)
    .plus(sourceSets["codeGen"].runtimeClasspath)
}

tasks.named<CheckForbiddenApis>("forbiddenApisCodeGen").configure {
  classpath = sourceSets["main"].output + sourceSets["codeGen"].output
  bundledSignatures.addAll(listOf("jdk-deprecated", "jdk-internal",
    "jdk-non-portable", "jdk-reflection", "jdk-system-out", "jdk-unsafe"))
}

tasks.named<CheckForbiddenApis>("forbiddenApisMain").configure {
  bundledSignatures.addAll(listOf("jdk-deprecated-18", "jdk-internal",
    "jdk-non-portable", "jdk-reflection", "jdk-system-out", "jdk-unsafe"))
}

tasks.named<CheckForbiddenApis>("forbiddenApisJavaPoet").configure {
  bundledSignatures.addAll(listOf("jdk-deprecated", "jdk-internal",
    "jdk-non-portable", "jdk-reflection", "jdk-unsafe"))
}

tasks.named<CheckForbiddenApis>("forbiddenApisTest").configure {
  bundledSignatures.addAll(listOf("jdk-deprecated-18", "jdk-unsafe"))
}

tasks.named<CheckForbiddenApis>("forbiddenApisJmh").configure {
  bundledSignatures.addAll(listOf("jdk-deprecated-18", "jdk-reflection", "jdk-unsafe"))
}

tasks.register<JavaExec>("memoryOverhead") {
  group = "Benchmarks"
  description = "Evaluates cache overhead"
  mainClass = "com.github.benmanes.caffeine.cache.MemoryBenchmark"
  classpath(sourceSets["jmh"].runtimeClasspath + sourceSets["codeGen"].runtimeClasspath)
  jvmArgs(
    "--add-opens", "java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens", "java.base/java.util.concurrent.locks=ALL-UNNAMED",
    "--add-opens", "java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang.ref=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    "--add-opens", "java.base/java.util=ALL-UNNAMED",
    "-javaagent:${jammAgent.get().asPath}")
}

tasks.register<Stress>("stress") {
  group = "Cache tests"
  description = "Executes a stress test"
  mainClass = "com.github.benmanes.caffeine.cache.Stresser"
  inputs.files(tasks.named<JavaCompile>("compileTestJava").map { it.outputs.files })
  classpath = sourceSets["codeGen"].runtimeClasspath + sourceSets["test"].runtimeClasspath
  outputs.upToDateWhen { false }
}

for (scenario in Scenario.all()) {
  scenario.apply {
    val task = tasks.register<Test>(testName()) {
      group = "Parameterized Test"
      description = "Runs tests with the given features."
      include("com/github/benmanes/caffeine/cache/**")

      val jitpack = providers.environmentVariable("JITPACK")
      onlyIf { !jitpack.isPresent }

      systemProperties(
        "keys" to keys.name,
        "stats" to stats.name,
        "values" to values.name,
        "compute" to compute.name,
        "implementation" to implementation)

      useTestNG {
        if (slow == Slow.Enabled) {
          maxParallelForks = 2
          includeGroups.add("slow")
          if (java.toolchain.languageVersion.get().canCompileOrRun(23)) {
            jvmArgs("-XX:+UseShenandoahGC")
          } else {
            jvmArgs("-XX:+UseParallelGC")
          }
        } else {
          parallel = "methods"
          excludeGroups("slow", "isolated", "lincheck")
          jvmArgs("-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED")
          threadCount = max(6, Runtime.getRuntime().availableProcessors() - 1)
        }
      }
    }
    tasks.named<Test>("test").configure {
      dependsOn(task)
    }
  }
}

eclipse {
  classpath {
    plusConfigurations.add(configurations["javaPoetCompileClasspath"])

    file.whenMerged {
      if (this is EclipseClasspath) {
        val regex = ".*collections4.*-tests.jar".toRegex()
        entries.filterIsInstance<Library>()
          .filter { regex.matches(it.path) }
          .forEach { it.sourcePath = fileReference(collections4Sources.singleFile) }
      }
    }
  }
  synchronizationTasks(generateLocalCaches, generateNodes)
}

idea.module {
  scopes["PROVIDED"]!!["plus"]!!.add(configurations["javaPoetCompileClasspath"])
}

abstract class Stress : JavaExec() {
  @get:Input
  @get:Option(option = "workload", description = "The workload type")
  abstract val operation: Property<String>

  @get:Input
  @get:Option(option = "duration", description = "The run duration (e.g. PT30S)")
  abstract val duration: Property<String>

  init {
    operation.convention("")
    duration.convention("")
  }

  @TaskAction
  override fun exec() {
    if (operation.get().isNotEmpty()) {
      args("--workload", operation.get())
    }
    if (duration.get().isNotEmpty()) {
      args("--duration", duration.get())
    }
    super.exec()
  }
}

data class Scenario(val implementation: Implementation,
                    val keys: KeyStrength, val values: ValueStrength,
                    val stats: Stats, val compute: Compute, val slow: Slow) {
  companion object {
    fun all(): List<Scenario> {
      return Sets.cartesianProduct(
        Slow.values().toSet(),
        Stats.values().toSet(),
        Compute.values().toSet(),
        KeyStrength.values().toSet(),
        ValueStrength.values().toSet(),
        Implementation.values().toSet(),
      ).map { features ->
        Scenario(
          slow = features[0] as Slow,
          stats = features[1] as Stats,
          compute = features[2] as Compute,
          keys = features[3] as KeyStrength,
          values = features[4] as ValueStrength,
          implementation = features[5] as Implementation)
      }.filter { it.isValid() }
    }
  }

  fun testName(): String = buildString {
    append(keys.name.lowercase()).append("Keys")
    append("And").append(values).append("Values")
    if (stats == Stats.Enabled) {
      append("Stats")
    }
    append(compute)
    append(implementation)
    if (slow == Slow.Enabled) {
      append("Slow")
    }
    append("Test")
  }

  fun isValid(): Boolean {
    val guavaIncompatible = (implementation == Implementation.Guava) && (compute == Compute.Async)
    val asyncIncompatible = (compute == Compute.Async) && (values != ValueStrength.Strong)
    val noSlowTests = (slow == Slow.Enabled)
      && (keys == KeyStrength.Strong) && (values == ValueStrength.Strong)
    return !guavaIncompatible && !asyncIncompatible && !noSlowTests
  }
}

enum class Implementation { Caffeine, Guava }
enum class ValueStrength { Strong, Weak, Soft }
enum class KeyStrength { Strong, Weak }
enum class Stats { Enabled, Disabled }
enum class Slow { Enabled, Disabled }
enum class Compute { Async, Sync }
