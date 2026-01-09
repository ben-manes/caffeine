import com.google.common.base.CaseFormat
import com.google.common.collect.Sets
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway
import org.gradle.api.tasks.testing.logging.TestLogEvent.STARTED
import org.gradle.plugins.ide.eclipse.model.Library
import org.gradle.plugins.ide.eclipse.model.SourceFolder
import org.gradle.plugins.ide.eclipse.model.Classpath as EclipseClasspath

plugins {
  id("java-library.caffeine")
  id("java-test-fixtures")
  id("jcstress.caffeine")
  id("revapi.caffeine")
  id("jmh.caffeine")
}

sourceSets {
  register("javaPoet") {
    java.srcDir("src/javaPoet/java")
  }
  register("codeGen") {
    java.srcDir(layout.buildDirectory.dir("generated/sources/node"))
    java.srcDir(layout.buildDirectory.dir("generated/sources/local-cache"))
  }
}

val compileJava by tasks.existing
val jar by tasks.existing(Jar::class)
val compileJavaPoetJava by tasks.existing
val jammAgent by configurations.registering
val collections4Sources by configurations.registering
val javaPoetImplementation by configurations.existing
val javaPoetRuntimeOnly by configurations.existing
val osgiBundleElements by configurations.registering {
  configureAsRuntimeOutgoing()
}

artifacts {
  add(osgiBundleElements.name, jar)
}

dependencies {
  api(libs.jspecify)
  api(libs.errorprone.annotations)

  testFixturesApi(libs.truth)
  testFixturesApi(libs.awaitility)
  testFixturesApi(libs.guava.testlib)
  testFixturesApi(libs.infer.annotations)
  testFixturesApi(libs.bundles.slf4j.test)

  testFixturesImplementation(libs.testng)
  testFixturesImplementation(libs.jctools)
  testFixturesImplementation(libs.mockito)
  testFixturesImplementation(libs.commons.lang3)

  testImplementation(sourceSets.named("codeGen").map { it.output })

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
  jmh(libs.java.`object`.layout)
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
  classpath = files(sourceSets.named("main").map { it.runtimeClasspath + it.output })
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
  classpath(sourceSets.named("javaPoet").map { it.runtimeClasspath })
  argumentProviders.add { listOf(outputDir.absolutePath().get()) }
  inputs.files(compileJavaPoetJava.map { it.outputs.files })
  outputs.dir(outputDir)
  outputs.cacheIf { true }
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
}

tasks.withType<JavaCompile>().configureEach {
  if (name.contains("Test") && name.contains("Java")) {
    options.apply {
      compilerArgs.add("-Xlint:-auxiliaryclass")
    }
  }
}

tasks.named<JavaCompile>("compileJmhJava").configure {
  options.apply {
    compilerArgs.add("-Xlint:-classfile")
  }
}

testing.suites {
  named<JvmTestSuite>("test") {
    dependencies {
      implementation(libs.ycsb) {
        isTransitive = false
      }
      implementation(libs.guava)
      implementation(libs.jazzer)
      implementation(libs.jctools)
      implementation(libs.mockito)
      implementation(libs.picocli)
      implementation(libs.lincheck)
      implementation(libs.awaitility)
      implementation(libs.junit.jupiter)
      implementation(libs.commons.lang3)
      implementation(libs.guava.testlib)
      implementation(libs.commons.collections4)
      implementation(libs.commons.collections4) {
        artifact { classifier = "tests" }
      }
      implementation.bundle(libs.bundles.slf4j.test)
      implementation(libs.eclipse.collections.testutils)
      implementation.bundle(libs.bundles.osgi.test.compile)

      runtimeOnly.bundle(libs.bundles.osgi.test.runtime)
    }
    targets {
      named("test") {
        testTask.configure {
          maxHeapSize = "3g"
          useTestNG {
            includeGroups("isolated")
          }
        }
      }
      all {
        useTestNG(libs.versions.testng)

        testTask.configure {
          testClassesDirs = files(sourceSets.named("test").map { it.output.classesDirs })
          classpath = files(
            sourceSets.named("test").map { it.runtimeClasspath },
            sourceSets.named("codeGen").map { it.runtimeClasspath })
        }
      }
      for (scenario in Scenario.all()) {
        scenario.apply {
          targets.register(testName()) {
            testTask.configure {
              group = "Parameterized Test"
              include("com/github/benmanes/caffeine/cache/**")

              systemProperties(
                "keys" to keys,
                "stats" to stats,
                "values" to values,
                "compute" to compute,
                "implementation" to implementation)
              jvmArgs("-XX:+UseParallelGC", "-XX:+ParallelRefProcEnabled",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "-XX:-ExplicitGCInvokesConcurrent")
              if (slow == Slow.Enabled) {
                maxParallelForks = Runtime.getRuntime().availableProcessors()
                useTestNG {
                  includeGroups.add("slow")
                }
              } else {
                useTestNG {
                  parallel = "methods"
                  excludeGroups("slow", "isolated")
                  threadCount = Runtime.getRuntime().availableProcessors()
                }
              }
            }
          }
        }
      }
    }
  }
  register<JvmTestSuite>("apacheTest") {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(project())
      implementation(testFixtures(project()))
      implementation(libs.commons.collections4)
      implementation(libs.commons.collections4) {
        artifact { classifier = "tests" }
      }
    }
    targets.all {
      testTask.configure {
        useParallelJUnitJupiter()
      }
    }
  }
  register<JvmTestSuite>("eclipseTest") {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(project())
      implementation(testFixtures(project()))
      implementation(libs.eclipse.collections.testutils)
    }
    targets.all {
      testTask.configure {
        useParallelJUnitJupiter()
      }
    }
  }
  register<JvmTestSuite>("fuzzTest") {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(project())
      implementation(libs.truth)
      implementation(libs.jazzer)
    }
    targets.all {
      testTask.configure {
        environment("JAZZER_FUZZ", "1")
        maxParallelForks = 2 * Runtime.getRuntime().availableProcessors()
        failFast = true
        forkEvery = 1
      }
    }
  }
  register<JvmTestSuite>("googleTest") {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(project())
      implementation(libs.truth)
      implementation(testFixtures(project()))

      runtimeOnly(libs.junit.jupiter.vintage)
    }
    targets.all {
      testTask.configure {
        useParallelJUnitJupiter()
      }
    }
  }
  register<JvmTestSuite>("moduleTest") {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(project())
      implementation(libs.guava)
    }
    targets.all {
      testTask.configure {
        useParallelJUnitJupiter()
      }
    }
  }
  register<JvmTestSuite>("jctoolsTest") {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(project())
      implementation(libs.truth)
      implementation(libs.jctools)

      runtimeOnly(libs.junit.jupiter.vintage)
    }
    targets.all {
      testTask.configure {
        useParallelJUnitJupiter()
      }
    }
  }
  register<JvmTestSuite>("jsr166Test") {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(project())
      implementation(libs.junit)
      implementation(libs.infer.annotations)

      runtimeOnly(libs.junit.jupiter.vintage)
    }
    targets.all {
      testTask.configure {
        useParallelJUnitJupiter()
      }
    }
  }
  register<JvmTestSuite>("lincheckTest") {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(project())
      implementation(libs.lincheck)
    }
    targets.all {
      testTask.configure {
        val isEnabled = isEarlyAccess().map { !it }
        testLogging.events(STARTED)
        onlyIf { isEnabled.get() }
        useParallelJUnitJupiter()
        maxHeapSize = "3g"
        failFast = true

        systemProperties(providers.systemPropertiesPrefixedBy("lincheck").get())
        jvmArgs("-XX:+UseParallelGC")

        // https://github.com/JetBrains/lincheck/issues/915
        if (java.toolchain.languageVersion.get().canCompileOrRun(25)) {
          jvmArgs("-XX:-UseCompactObjectHeaders")
        }
      }
    }
  }
  register("openjdkTest", JvmTestSuite::class) {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(project())
      implementation(libs.truth)
      implementation(libs.testng)
      implementation(libs.infer.annotations)

      runtimeOnly(libs.junit.jupiter.testng)
    }
    targets.all {
      testTask.configure {
        useParallelJUnitJupiter()
      }
    }
  }
  register<JvmTestSuite>("osgiTest") {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(project())
      implementation.bundle(libs.bundles.osgi.test.compile)

      runtimeOnly(libs.junit.jupiter.vintage)
      runtimeOnly.bundle(libs.bundles.osgi.test.runtime)
    }
    targets.all {
      testTask.configure {
        useParallelJUnitJupiter()
        val relativeDir = projectDir
        inputs.files(jar.map { it.outputs.files })
        val jarPath = jar.flatMap { it.archiveFile }.relativePathFrom(relativeDir)
        doFirst { systemProperty("caffeine.osgi.jar", jarPath.get()) }
      }
    }
  }
}

tasks.named<Jar>("jar").configure {
  from(sourceSets.named("main").map { it.output })
  from(sourceSets.named("codeGen").map { it.output })
  inputs.files(compileCodeGenJava.map { it.outputs.files })
  bundle.bnd(mapOf(
    "Bundle-SymbolicName" to "com.github.ben-manes.caffeine",
    "Import-Package" to "",
    "Export-Package" to listOf(
      "com.github.benmanes.caffeine",
      "com.github.benmanes.caffeine.cache",
      "com.github.benmanes.caffeine.cache.stats").joinToString(",")))
}

tasks.named<Jar>("sourcesJar").configure {
  inputs.files(generateLocalCaches.map { it.outputs.files })
  inputs.files(generateNodes.map { it.outputs.files })
  from(sourceSets.named("codeGen").map { it.allSource })
}

tasks.named<Javadoc>("javadoc").configure {
  mustRunAfter(compileCodeGenJava)
}

tasks.named<Javadoc>("testFixturesJavadoc").configure {
  javadocOptions {
    addBooleanOption("Xdoclint:all,-missing", true)
  }
}

tasks.named<CheckForbiddenApis>("forbiddenApisCodeGen").configure {
  classpath = files(
    sourceSets.named("main").map { it.output },
    sourceSets.named("codeGen").map { it.output })
}

tasks.withType<CheckForbiddenApis>().configureEach {
  bundledSignatures.addAll(when (name) {
    "forbiddenApisJavaPoet", "forbiddenApisJctoolsTest", "forbiddenApisJsr166Test" -> listOf(
      "jdk-deprecated-18", "jdk-internal", "jdk-non-portable", "jdk-reflection", "jdk-unsafe")
    "forbiddenApisOpenjdkTest", "forbiddenApisTestFixtures" -> listOf("jdk-deprecated-18",
      "jdk-internal", "jdk-non-portable", "jdk-unsafe")
    "forbiddenApisTest" -> listOf("jdk-deprecated-18", "jdk-internal", "jdk-non-portable")
    "forbiddenApisJmh" -> listOf("jdk-deprecated-18", "jdk-reflection", "jdk-unsafe")
    else -> listOf("jdk-deprecated-18", "jdk-internal", "jdk-non-portable",
        "jdk-reflection", "jdk-system-out", "jdk-unsafe")
  })
}

tasks.register<JavaExec>("memoryOverhead") {
  group = "Benchmarks"
  description = "Evaluates cache overhead"
  mainClass = "com.github.benmanes.caffeine.cache.MemoryBenchmark"
  systemProperties("jdk.attach.allowAttachSelf" to "true", "jol.tryWithSudo" to "true")
  val javaAgent = jammAgent.map { it.asPath }
  classpath(tasks.named("jmhJar"))
  jvmArgumentProviders.add {
    listOf(
      "--add-opens", "java.base/java.util.concurrent.atomic=ALL-UNNAMED",
      "--add-opens", "java.base/java.util.concurrent.locks=ALL-UNNAMED",
      "--add-opens", "java.base/java.util.concurrent=ALL-UNNAMED",
      "--add-opens", "java.base/java.lang.ref=ALL-UNNAMED",
      "--add-opens", "java.base/java.lang=ALL-UNNAMED",
      "--add-opens", "java.base/java.util=ALL-UNNAMED",
      "-Djdk.attach.allowAttachSelf=true",
      "-XX:+EnableDynamicAgentLoading",
      "-javaagent:${javaAgent.get()}",
      "-XX:+UseCompactObjectHeaders",
      "-Djol.magicFieldOffset=true")
  }
}

tasks.register<Stress>("stress") {
  group = "Verification"
  description = "Executes a stress test"
  mainClass = "com.github.benmanes.caffeine.cache.Stresser"
  inputs.files(tasks.named<JavaCompile>("compileTestJava").map { it.outputs.files })
  classpath(
    sourceSets.named("codeGen").map { it.runtimeClasspath },
    sourceSets.named("test").map { it.runtimeClasspath })
  outputs.upToDateWhen { false }
}

val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations.testFixturesApiElements.get()) {
  skip()
}
javaComponent.withVariantsFromConfiguration(configurations.testFixturesRuntimeElements.get()) {
  skip()
}

eclipse {
  classpath {
    plusConfigurations.add(configurations["javaPoetCompileClasspath"])

    file.whenMerged {
      if (this is EclipseClasspath) {
        val collectionsRegex = ".*collections4.*-tests.jar".toRegex()
        entries.filterIsInstance<Library>()
          .filter { collectionsRegex.matches(it.path) }
          .forEach {
            it.sourcePath = fileReference(
              file(collections4Sources.map { sources -> sources.singleFile }))
          }

        val testRegex = "src/(.*Test|testFixtures)/java".toRegex()
        entries.filterIsInstance<SourceFolder>()
          .filter { testRegex.matches(it.path) }
          .forEach {
            it.entryAttributes["test"] = "true"
            it.entryAttributes["module"] = "false"
          }
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
    argumentProviders.add {
      buildList {
        if (operation.get().isNotEmpty()) {
          addAll(listOf("--workload", operation.get()))
        }
        if (duration.get().isNotEmpty()) {
          addAll(listOf("--duration", duration.get()))
        }
      }
    }
  }
}

data class Scenario(val implementation: Implementation,
                    val keys: KeyStrength, val values: ValueStrength,
                    val stats: Stats, val compute: Compute, val slow: Slow) {
  companion object {
    fun all(): List<Scenario> {
      return Sets.cartesianProduct(
        Slow.entries.toSet(),
        Stats.entries.toSet(),
        Compute.entries.toSet(),
        KeyStrength.entries.toSet(),
        ValueStrength.entries.toSet(),
        Implementation.entries.toSet(),
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
