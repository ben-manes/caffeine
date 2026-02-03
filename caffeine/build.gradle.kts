@file:Suppress("UnstableApiUsage")
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
  testFixturesApi(libs.junit.jupiter)
  testFixturesApi(libs.guava.testlib)
  testFixturesApi(libs.infer.annotations)
  testFixturesApi(libs.bundles.slf4j.test)
  testFixturesApi(libs.spotbugs.annotations)

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
  javaPoetImplementation(libs.spotbugs.annotations)

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
      disable("IdentifierName")
      disable("Varifier")
      disable("Var")
      nullaway.disable()
    }
  }
}

val generateLocalCaches by tasks.registering(JavaExec::class) {
  codeGenerationTask("LocalCache", "local-cache")
}

val generateNodes by tasks.registering(JavaExec::class) {
  codeGenerationTask("Node", "node")
}

private fun JavaExec.codeGenerationTask(generator: String, directory: String) {
  mainClass = "com.github.benmanes.caffeine.cache.${generator}FactoryGenerator"
  val outputDir = layout.buildDirectory.dir("generated/sources/$directory")
  classpath(sourceSets.named("javaPoet").map { it.runtimeClasspath })
  argumentProviders.add { listOf(outputDir.absolutePath().get()) }
  inputs.files(compileJavaPoetJava.map { it.outputs.files })
  outputs.cacheIf { true }
  outputs.dir(outputDir)
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
    useJUnitJupiter(libs.versions.junit.jupiter)

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
          classpath = files(sourceSets.named("test").map { it.runtimeClasspath },
            sourceSets.named("codeGen").map { it.runtimeClasspath })
          testClassesDirs = files(sourceSets.named("test").map { it.output.classesDirs })
          jvmArgs("-XX:+UseParallelGC", "-XX:+ParallelRefProcEnabled",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED")

          val testOptions = listOf("implementation", "compute", "keys", "values", "stats")
            .associateWith { providers.gradleProperty(it) }
          inputs.properties(testOptions.filterValues { it.isPresent })
          systemProperties(testOptions.mapValues { it.value.orNull })

          var shardingOptions = mapOf(
            "shardCount" to providers.gradleProperty("shardCount")
              .map { it.toIntOrNull() }.getOrElse(1).coerceAtLeast(1),
            "shardIndex" to providers.gradleProperty("shardIndex")
              .map { it.toIntOrNull() }.getOrElse(0))
          inputs.properties(shardingOptions)
          systemProperties(shardingOptions)
          useParallelJUnitJupiter()
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
        failOnSkippedTests()
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
        failOnSkippedTests()
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
      runtimeOnly.bundle(libs.bundles.slf4j.nop)
    }
    targets.all {
      testTask.configure {
        maxParallelForks = Runtime.getRuntime().availableProcessors()
        // https://github.com/CodeIntelligenceTesting/jazzer/issues/1035
        if (java.toolchain.languageVersion.get().canCompileOrRun(25)) {
          configure<JacocoTaskExtension> {
            isEnabled = false
          }
        }
        environment("JAZZER_FUZZ", "1")
        failOnSkippedTests()
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
        failOnSkippedTests()
        useParallelJUnitJupiter()
      }
    }
  }
  register<JvmTestSuite>("moduleTest") {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(project())
      implementation(libs.guava)
      runtimeOnly.bundle(libs.bundles.slf4j.nop)
    }
    targets.all {
      testTask.configure {
        failOnSkippedTests()
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
      implementation(libs.hamcrest)
      implementation(libs.spotbugs.annotations)

      runtimeOnly(libs.junit.jupiter.vintage)
      runtimeOnly.bundle(libs.bundles.slf4j.nop)
    }
    targets.all {
      testTask.configure {
        failOnSkippedTests()
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
      implementation(libs.spotbugs.annotations)

      runtimeOnly(libs.junit.jupiter.vintage)
      runtimeOnly.bundle(libs.bundles.slf4j.nop)
    }
    targets.all {
      testTask.configure {
        failOnSkippedTests()
        useParallelJUnitJupiter()
      }
    }
  }
  register<JvmTestSuite>("lincheckTest") {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(project())
      implementation(libs.lincheck)
      runtimeOnly.bundle(libs.bundles.slf4j.nop)
    }
    targets.all {
      testTask.configure {
        val isEnabled = isEarlyAccess().map { !it }
        testLogging.events(STARTED)
        onlyIf { isEnabled.get() }
        useParallelJUnitJupiter()
        failOnSkippedTests()
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
      implementation(libs.spotbugs.annotations)

      runtimeOnly(libs.junit.jupiter.testng)
      runtimeOnly.bundle(libs.bundles.slf4j.nop)
    }
    targets.all {
      testTask.configure {
        failOnSkippedTests()
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
      runtimeOnly.bundle(libs.bundles.slf4j.nop)
      runtimeOnly.bundle(libs.bundles.osgi.test.runtime)
    }
    targets.all {
      testTask.configure {
        failOnSkippedTests()
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
