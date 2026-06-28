@file:Suppress("PackageDirectoryMismatch")
import org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE
import org.gradle.api.tasks.PathSensitivity.RELATIVE

plugins {
  `java-library`
}

val roseau = configurations.register("roseau")

dependencies {
  roseau(libs.roseau)
  roseau(libs.slf4j.nop)
}

tasks.register<Roseau>("roseau") {
  group = "Verification"
  description = "Checks for breaking API changes"
  currentJar = tasks.jar.flatMap { it.archiveFile }
  previous = "${project.group}:${project.name}:${libs.caffeine.get().version}"
  config = rootProject.layout.projectDirectory.file("gradle/config/roseau/config.yaml")
  compileClasspath.from(configurations.compileClasspath.map { it.incoming.artifactView {
    attributes { attribute(LIBRARY_ELEMENTS_ATTRIBUTE,
      objects.named(LibraryElements::class.java, LibraryElements.JAR))
    }
  }.files})
  classpath(roseau)
}

@CacheableTask
abstract class Roseau @Inject constructor(@Internal val projectLayout: ProjectLayout) : JavaExec() {
  @get:Input
  abstract val previous: Property<String>
  @get:InputFile @get:PathSensitive(RELATIVE)
  abstract val currentJar: RegularFileProperty
  @get:InputFiles @get:CompileClasspath
  abstract val compileClasspath: ConfigurableFileCollection
  @get:InputFile @get:PathSensitive(RELATIVE)
  abstract val config: RegularFileProperty
  @get:OutputFile
  val report: Provider<RegularFile> = projectLayout.buildDirectory.file("reports/$name/report.html")

  init {
    isIgnoreExitValue = true
    mainClass = "io.github.alien.roseau.cli.RoseauCLI"
    argumentProviders.add(object : CommandLineArgumentProvider {
      @get:Internal val compileClasspath = this@Roseau.compileClasspath
      @get:Internal val currentJar = this@Roseau.currentJar
      @get:Internal val config = this@Roseau.config
      @get:Internal val report = this@Roseau.report
      override fun asArguments(): Iterable<String> = listOf(
        "--diff",
        "--fail-on-bc",
        "--v1", previous.get(),
        "--v2", currentJar.absolutePath().get(),
        "--config", config.absolutePath().get(),
        "--classpath", compileClasspath.asPath,
        "--report", "HTML=${report.absolutePath().get()}")
    })
    doLast {
      val result = executionResult.get()
      if (result.exitValue != 0) {
        logger.error("Roseau found breaking changes: ${report.get().asFile}")
      }
      result.assertNormalExitValue()
    }
  }
}
