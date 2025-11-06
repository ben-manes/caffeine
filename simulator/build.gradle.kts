/** Cache simulator using tracing data and a family of eviction policy options. */
import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway

plugins {
  id("application")
  id("java-library.caffeine")
}

dependencies {
  implementation(project(":caffeine"))

  implementation(libs.xz)
  implementation(libs.ycsb) {
    isTransitive = false
  }
  implementation(libs.zstd)
  implementation(libs.guava)
  implementation(libs.config)
  implementation(libs.stream)
  implementation(libs.tcache)
  implementation(libs.cache2k)
  implementation(libs.fastcsv)
  implementation(libs.picocli)
  implementation(libs.ehcache3)
  implementation(libs.fastutil)
  implementation(libs.hazelcast)
  implementation(libs.jfreechart)
  implementation(libs.fast.filter)
  implementation(libs.flip.tables)
  implementation(libs.expiring.map)
  implementation(libs.commons.lang3)
  implementation(libs.commons.math3)
  implementation(libs.commons.compress)
  implementation(libs.bundles.coherence)
  implementation(libs.bundles.slf4j.jdk)
  implementation(libs.zero.allocation.hashing)

  compileOnly(libs.spotbugs.annotations)
}

application {
  mainClass = "com.github.benmanes.caffeine.cache.simulator.Simulator"
}

java.toolchain.languageVersion = javaRuntimeVersion()

testing.suites {
  named<JvmTestSuite>("test") {
    useJUnitJupiter(libs.versions.junit.jupiter)

    dependencies {
      implementation(libs.truth)
    }
  }
}

forbiddenApis {
  bundledSignatures.addAll(listOf("jdk-deprecated", "jdk-internal",
    "jdk-non-portable", "jdk-reflection", "jdk-unsafe"))
}

tasks.named<JavaCompile>("compileJava").configure {
  options.apply {
    compilerArgs.addAll(listOf("-Xlint:-classfile", "-Xlint:-processing"))
    errorprone {
      disable("SystemOut")

      nullaway {
        externalInitAnnotations.add("picocli.CommandLine.Command")
      }
    }
  }
}

tasks.named<Jar>("jar").configure {
  bundle.bnd(mapOf(
    "Bundle-SymbolicName" to "com.github.benmanes.caffeine.simulator",
    "Automatic-Module-Name" to "com.github.benmanes.caffeine.simulator"))
}

tasks.withType<Javadoc>().configureEach {
  javadocOptions {
    addBooleanOption("Xdoclint:all,-missing", true)
  }
}

tasks.named<JavaExec>("run").configure {
  description = "Runs a single simulation and generates a report"
}

val runTasks = listOf(
  tasks.named<JavaExec>("run"),
  tasks.register<Rewrite>("rewrite"),
  tasks.register<Simulate>("simulate"))

runTasks.forEach { task ->
  task.configure {
    inputs.files(tasks.named<ProcessResources>("processResources").map { it.outputs.files })
    inputs.files(tasks.named<JavaCompile>("compileJava").map { it.outputs.files })
    classpath(sourceSets.named("main").map { it.runtimeClasspath })
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }

    val defaultJvmArguments = defaultJvmArgs()
    jvmArgumentProviders.add { defaultJvmArguments.get() }
    val overrides = providers.systemPropertiesPrefixedBy("caffeine")
    doFirst {
      systemProperties(overrides.get())
    }
  }
}

abstract class Simulate @Inject constructor(
                        @Internal val projectLayout: ProjectLayout) : JavaExec() {
  @get:Input @get:Option(option = "maximumSize", description = "The maximum sizes")
  abstract val maximumSize: ListProperty<String>
  @get:Input @get:Option(option = "metric", description = "The metric to compare")
  abstract val metric: Property<String>
  @get:Input @get:Option(option = "theme", description = "The chart theme")
  abstract val theme: Property<String>
  @get:Input @get:Option(option = "title", description = "The chart title")
  abstract val title: Property<String>
  @get:OutputDirectory
  val reportDir: Provider<Directory> = projectLayout.buildDirectory.dir("reports/$name")

  init {
    group = "Application"
    mainClass = "com.github.benmanes.caffeine.cache.simulator.Simulate"
    description = "Runs multiple simulations and generates an aggregate report"
    maximumSize.convention(emptyList())
    metric.convention("Hit Rate")
    theme.convention("light")
    title.convention("")
    argumentProviders.add {
      buildList {
        if (maximumSize.get().isNotEmpty()) {
          addAll(listOf("--maximumSize", maximumSize.get().joinToString(",")))
        }
        addAll(listOf("--outputDir", reportDir.get().asFile.path))
        addAll(listOf("--metric", metric.get()))
        addAll(listOf("--title", title.get()))
        addAll(listOf("--theme", theme.get()))
      }
    }
  }
}

abstract class Rewrite : JavaExec() {
  @get:Input @get:Option(option = "inputFiles", description = "The trace input files")
  abstract val inputFiles: ListProperty<String>
  @get:Input @get:Option(option = "inputFormat", description = "The input format")
  abstract val inputFormat: Property<String>
  @get:Input @get:Option(option = "outputFile", description = "The output file")
  abstract val outputFile: Property<String>
  @get:Input @get:Option(option = "outputFormat", description = "The output format")
  abstract val outputFormat: Property<String>

  init {
    group = "Application"
    description = "Rewrite traces into the format used by other simulators"
    mainClass = "com.github.benmanes.caffeine.cache.simulator.parser.Rewriter"
    inputFiles.convention(emptyList())
    outputFormat.convention("")
    inputFormat.convention("")
    outputFile.convention("")
    argumentProviders.add {
      buildList {
        if (inputFiles.get().isNotEmpty()) {
          addAll(listOf("--inputFiles", inputFiles.get().joinToString(",")))
        }
        if (outputFormat.get().isNotEmpty()) {
          addAll(listOf("--outputFormat", outputFormat.get()))
        }
        if (inputFormat.get().isNotEmpty()) {
          addAll(listOf("--inputFormat", inputFormat.get()))
        }
        if (outputFile.get().isNotEmpty()) {
          addAll(listOf("--outputFile", outputFile.get()))
        }
      }
    }
  }
}
