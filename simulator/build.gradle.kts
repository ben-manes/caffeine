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
  group = "Application"
  description = "Runs multiple simulations and generates an aggregate report"
  dependsOn(tasks.processResources, tasks.compileJava)
  classpath.set(sourceSets["main"].runtimeClasspath)
  systemProperties.set(caffeineSystemProperties())
  defaultJvmArgs.set(javaExecJvmArgs())
  outputs.upToDateWhen { false }
}

tasks.register<Rewrite>("rewrite") {
  group = "Application"
  description = "Rewrite traces into the format used by other simulators"
  dependsOn(tasks.processResources, tasks.compileJava)
  classpath.set(sourceSets["main"].runtimeClasspath)
  outputs.upToDateWhen { false }
}

eclipse.classpath.file.beforeMerged {
  if (this is EclipseClasspath) {
    entries.add(SourceFolder(
      relativePath("$buildDir/generated/sources/annotationProcessor/java/main"), "bin/main"))
  }
}

abstract class Simulate @Inject constructor(@Internal val external: ExecOperations,
                                            @Internal val layout: ProjectLayout) : DefaultTask() {
  @get:Input @get:Optional @get:Option(option = "jvmArgs", description = "The jvm arguments")
  abstract val jvmOptions: Property<String>
  @Input @Option(option = "maximumSize", description = "The maximum sizes")
  var maximumSize = ""
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
    external.javaexec {
      mainClass.set("com.github.benmanes.caffeine.cache.simulator.Simulate")
      systemProperties(this@Simulate.systemProperties.get())
      classpath(this@Simulate.classpath)
      jvmArgs(defaultJvmArgs.get())

      if (maximumSize.isNotEmpty()) {
        args("--maximumSize", maximumSize)
      }
      args("--outputDir", reportDir)
      args("--metric", metric)
      args("--title", title)
      args("--theme", theme)
    }
  }
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
