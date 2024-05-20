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
  implementation(libs.zero.allocation.hashing)
}

application {
  mainClass = "com.github.benmanes.caffeine.cache.simulator.Simulator"
}

forbiddenApis {
  bundledSignatures.addAll(listOf("commons-io-unsafe-2.15.1", "jdk-deprecated",
    "jdk-internal", "jdk-non-portable", "jdk-reflection", "jdk-unsafe"))
}

tasks.withType<JavaCompile>().configureEach {
  options.errorprone {
    disableWarningsInGeneratedCode = true
    nullaway.disable()
  }
  modularity.inferModulePath = true
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}

tasks.jar {
  bundle.bnd(mapOf(
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

tasks.register<Simulate>("simulate") {
  description = "Runs multiple simulations and generates an aggregate report"
  mainClass = "com.github.benmanes.caffeine.cache.simulator.Simulate"
  configureSimulatorTask()
}

tasks.register<Rewrite>("rewrite") {
  description = "Rewrite traces into the format used by other simulators"
  mainClass = "com.github.benmanes.caffeine.cache.simulator.parser.Rewriter"
  configureSimulatorTask()
}

eclipse.classpath.file.beforeMerged {
  if (this is EclipseClasspath) {
    val absolutePath = layout.buildDirectory.dir("generated/sources/annotationProcessor/java/main")
    entries.add(SourceFolder(relativePath(absolutePath), "bin/main"))
  }
}

fun JavaExec.configureSimulatorTask() {
  group = "Application"
  dependsOn(tasks.processResources, tasks.compileJava)
  classpath(sourceSets["main"].runtimeClasspath)
  systemProperties(caffeineSystemProperties())
  outputs.upToDateWhen { false }
  jvmArgs(javaExecJvmArgs())
}

abstract class Simulate @Inject constructor(
                        @Internal val projectLayout: ProjectLayout) : JavaExec() {
  @Input @Option(option = "maximumSize", description = "The maximum sizes")
  var maximumSize: List<String> = emptyList()
  @Input @Option(option = "metric", description = "The metric to compare")
  var metric = "Hit Rate"
  @Input @Option(option = "theme", description = "The chart theme")
  var theme = "light"
  @Input @Option(option = "title", description = "The chart title")
  var title = ""
  @OutputDirectory
  val reportDir = File(projectLayout.buildDirectory.get().asFile, "/reports/$name")

  @TaskAction
  override fun exec() {
    if (maximumSize.isNotEmpty()) {
      args("--maximumSize", maximumSize.joinToString(","))
    }
    args("--outputDir", reportDir)
    args("--metric", metric)
    args("--title", title)
    args("--theme", theme)
    super.exec()
  }
}

abstract class Rewrite : JavaExec() {
  @Input @Option(option = "inputFiles", description = "The trace input files")
  var inputFiles: List<String> = emptyList()
  @Input @Option(option = "inputFormat", description = "The input format")
  var inputFormat: String = ""
  @Input @Option(option = "outputFile", description = "The output file")
  var outputFile: String = ""
  @Input @Option(option = "outputFormat", description = "The output format")
  var outputFormat: String = ""

  @TaskAction
  override fun exec() {
    if (inputFiles.isNotEmpty()) {
      args("--inputFiles", inputFiles.joinToString(","))
    }
    if (outputFormat.isNotEmpty()) {
      args("--outputFormat", outputFormat)
    }
    if (inputFormat.isNotEmpty()) {
      args("--inputFormat", inputFormat)
    }
    if (outputFile.isNotEmpty()) {
      args("--outputFile", outputFile)
    }
    super.exec()
  }
}
