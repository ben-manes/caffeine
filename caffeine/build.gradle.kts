import com.google.common.collect.Sets
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import kotlin.math.max
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.plugins.ide.eclipse.model.Classpath as EclipseClasspath
import org.gradle.plugins.ide.eclipse.model.SourceFolder

plugins {
  id("java-library-caffeine-conventions")
  id("jmh-caffeine-conventions")
}

sourceSets {
  create("javaPoet") {
    java.srcDir("src/javaPoet/java")
  }
  create("codeGen") {
    java.srcDir(layout.buildDirectory.dir("generated-sources/local"))
    java.srcDir(layout.buildDirectory.dir("generated-sources/nodes"))
  }
}

val compileJavaPoetJava by tasks.existing
val javaAgent: Configuration by configurations.creating
var javaPoetImplementation: Configuration = configurations["javaPoetImplementation"]

dependencies {
  api(libs.checker.annotations)
  api(libs.errorprone.annotations)

  testImplementation(libs.joor)
  testImplementation(libs.ycsb) {
    isTransitive = false
  }
  testImplementation(libs.picocli)
  testImplementation(libs.jctools)
  testImplementation(libs.fastutil)
  testImplementation(libs.lincheck)
  testImplementation(libs.guava.testlib)
  testImplementation(libs.commons.lang3)
  testImplementation(libs.bundles.slf4j.test)
  testImplementation(libs.commons.collections4)
  testImplementation(libs.commons.collections4) {
    artifact {
      classifier = "tests"
    }
  }
  testImplementation(libs.eclipse.collections.testutils)

  javaAgent(libs.jamm)

  jmh(libs.jamm)
  jmh(libs.tcache)
  jmh(libs.cache2k)
  jmh(libs.ehcache3)
  jmh(libs.hazelcast)
  jmh(libs.slf4j.nop)
  jmh(libs.jackrabbit)
  jmh(libs.flip.tables)
  jmh(libs.expiring.map)
  jmh(libs.bundles.coherence)
  jmh(libs.concurrentlinkedhashmap)
  jmh(sourceSets["codeGen"].output)

  javaPoetImplementation(libs.guava)
  javaPoetImplementation(libs.javapoet)
  javaPoetImplementation(libs.commons.lang3)
  javaPoetImplementation(libs.google.java.format)
}

val compileCodeGenJava by tasks.existing(JavaCompile::class) {
  classpath = sourceSets["main"].runtimeClasspath + sourceSets["main"].output
  dependsOn(tasks.compileJava)
  options.isDebug = false
}

compileJavaPoetJava.configure {
  finalizedBy(generateLocalCaches, generateNodes)
}

val generateLocalCaches by tasks.registering(JavaExec::class) {
  mainClass.set("com.github.benmanes.caffeine.cache.LocalCacheFactoryGenerator")
  outputs.dir(layout.buildDirectory.dir("generated-sources/local"))
    .withPropertyName("outputDir")
  inputs.files(sourceSets["javaPoet"].output)
    .withPropertyName("javaPoetOutput")
    .withPathSensitivity(RELATIVE)
  classpath = sourceSets["javaPoet"].runtimeClasspath
  args("build/generated-sources/local")
  dependsOn(compileJavaPoetJava)
  outputs.cacheIf { true }
}

val generateNodes by tasks.registering(JavaExec::class) {
  mainClass.set("com.github.benmanes.caffeine.cache.NodeFactoryGenerator")
  outputs.dir(layout.buildDirectory.dir("generated-sources/nodes"))
    .withPropertyName("outputDir")
  inputs.files(sourceSets["javaPoet"].output)
    .withPropertyName("javaPoetOutput")
    .withPathSensitivity(RELATIVE)
  classpath = sourceSets["javaPoet"].runtimeClasspath
  args("build/generated-sources/nodes")
  dependsOn(compileJavaPoetJava)
  outputs.cacheIf { true }
}

tasks.named<JavaCompile>("compileJava").configure {
  dependsOn(generateLocalCaches, generateNodes)
  finalizedBy(compileCodeGenJava)
  options.errorprone {
    disable("CheckReturnValue")
  }
}

tasks.named<JavaCompile>("compileTestJava").configure {
  dependsOn(tasks.jar, compileCodeGenJava)
}

tasks.test.configure {
  exclude("com/github/benmanes/caffeine/cache/**")
  dependsOn(junitTest)

  useTestNG {
    threadCount = max(6, Runtime.getRuntime().availableProcessors() - 1)
    jvmArgs("-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled")
    excludeGroups("slow", "isolated", "lincheck")
    parallel = "methods"
  }
}

tasks.register<Test>("isolatedTest") {
  group = "Verification"
  description = "Tests that must be run in isolation"
  useTestNG {
    includeGroups("isolated")
    maxHeapSize = "3g"
  }
}

tasks.register<Test>("lincheckTest") {
  group = "Verification"
  description = "Tests that assert linearizability"
  enabled = (System.getenv("JDK_EA") != "true")
  useTestNG {
    jvmArgs(
      "--add-opens", "java.base/jdk.internal.vm=ALL-UNNAMED",
      "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
      "--add-opens", "java.base/jdk.internal.access=ALL-UNNAMED",
      "--add-exports", "java.base/jdk.internal.util=ALL-UNNAMED")
    testLogging.events("started")
    includeGroups("lincheck")
    maxHeapSize = "3g"
    failFast = true
  }
}

val junitTest = tasks.register<Test>("junitTest") {
  group = "Verification"
  description = "JUnit tests"

  val jar by tasks.existing(Jar::class)
  dependsOn(jar)

  useJUnit()
  failFast = true
  maxHeapSize = "2g"
  systemProperty("caffeine.osgi.jar", relativePath(jar.get().archiveFile.get().asFile.path))
}

tasks.jar.configure {
  from(sourceSets["main"].output + sourceSets["codeGen"].output)
  dependsOn(compileCodeGenJava)
  applyOsgi(this, mapOf(
    "Bundle-SymbolicName" to "com.github.ben-manes.caffeine",
    "Import-Package" to "",
    "Export-Package" to listOf(
      "com.github.benmanes.caffeine",
      "com.github.benmanes.caffeine.cache",
      "com.github.benmanes.caffeine.cache.stats").joinToString(","),
    "Automatic-Module-Name" to "com.github.benmanes.caffeine"))
}

tasks.named<Jar>("sourcesJar").configure {
  dependsOn(generateLocalCaches, generateNodes)
}

tasks.named<Javadoc>("javadoc").configure {
  mustRunAfter(compileCodeGenJava)
}

val test by testing.suites.existing(JvmTestSuite::class)
tasks.withType<Test>().configureEach {
  classpath = files(test.map { it.sources.runtimeClasspath })
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
    "jdk-non-portable", "jdk-reflection", "jdk-system-out", "jdk-unsafe"))
}

tasks.named<CheckForbiddenApis>("forbiddenApisTest").configure {
  bundledSignatures.add("jdk-deprecated-18")
}

tasks.named<CheckForbiddenApis>("forbiddenApisJmh").configure {
  bundledSignatures.addAll(listOf("jdk-deprecated-18", "jdk-reflection"))
}

idea.module {
  scopes["PROVIDED"]!!["plus"]!!.add(configurations["javaPoetCompileClasspath"])
}

eclipse.classpath.file.whenMerged {
  if (this is EclipseClasspath) {
    entries.filterIsInstance<SourceFolder>()
      .filter { it.output == "bin/codeGen" }
      .forEach { it.output = "bin/main" }
  }
}

plugins.withType<EclipsePlugin>().configureEach {
  project.eclipse.classpath.plusConfigurations.add(configurations["javaPoetCompileClasspath"])
}

for (scenario in Scenario.all()) {
  scenario.apply {
    val task = tasks.register<Test>(scenario.testName()) {
      group = "Parameterized Test"
      description = "Runs tests with the given features."

      enabled = (System.getenv("JITPACK") != "true")
      include("com/github/benmanes/caffeine/cache/**")

      systemProperties(
        "keys" to keys.name,
        "stats" to stats.name,
        "values" to values.name,
        "compute" to compute.name,
        "implementation" to implementation)

      useTestNG {
        if (slow == Slow.Disabled) {
          threadCount = max(6, Runtime.getRuntime().availableProcessors() - 1)
          jvmArgs("-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled")
          excludeGroups("slow", "isolated", "lincheck")
          parallel = "methods"
        } else {
          jvmArgs("-XX:+UseParallelGC")
          includeGroups.add("slow")
          maxParallelForks = 2
        }
      }
    }
    tasks.test.configure {
      dependsOn(task)
    }
  }
}

tasks.register<JavaExec>("memoryOverhead") {
  group = "Benchmarks"
  description = "Evaluates cache overhead"
  mainClass.set("com.github.benmanes.caffeine.cache.MemoryBenchmark")
  classpath(sourceSets["jmh"].runtimeClasspath + sourceSets["codeGen"].runtimeClasspath)
  jvmArgs(
    "--add-opens", "java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens", "java.base/java.util.concurrent.locks=ALL-UNNAMED",
    "--add-opens", "java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang.ref=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    "--add-opens", "java.base/java.util=ALL-UNNAMED",
    "-javaagent:${configurations["javaAgent"].singleFile}")
}

abstract class Stress @Inject constructor(@Internal val external: ExecOperations) : DefaultTask() {
  @get:Input @get:Optional @get:Option(option = "workload", description = "The workload type")
  abstract val operation: Property<String>
  @get:InputFiles @get:Classpath
  abstract val classpath: Property<FileCollection>

  @TaskAction
  fun run() {
    external.javaexec {
      mainClass.set("com.github.benmanes.caffeine.cache.Stresser")
      classpath(this@Stress.classpath)
      if (operation.isPresent) {
        args("--workload", operation.get())
      } else {
        args("--help")
      }
    }
  }
}
tasks.register<Stress>("stress") {
  group = "Cache tests"
  description = "Executes a stress test"
  classpath.set(sourceSets["codeGen"].runtimeClasspath + sourceSets["test"].runtimeClasspath)
  outputs.upToDateWhen { false }
  dependsOn(tasks.compileTestJava)
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
    append(keys.name.lowercase() + "Keys")
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
