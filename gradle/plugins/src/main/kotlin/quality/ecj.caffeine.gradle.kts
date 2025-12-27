@file:Suppress("PackageDirectoryMismatch")
import org.gradle.api.tasks.PathSensitivity.RELATIVE

plugins {
  `jvm-ecosystem`
}

val ecj by configurations.registering

dependencies {
  ecj(libs.ecj)
}

sourceSets.configureEach {
  val compileTask = tasks.named<JavaCompile>(compileJavaTaskName)
  tasks.register<EclipseJavaCompile>(getTaskName("ecj", /* target= */ null)) {
    description = "Run ECJ analysis for ${this@configureEach.name} classes"
    properties = rootProject.layout.projectDirectory.file(
      "gradle/config/eclipse/org.eclipse.jdt.core.prefs")
    compileClasspath = compileTask.map { it.classpath }
    inputs.files(compileTask.map { it.outputs.files })
    javaSources = allJava.asFileTree
    classpath(ecj)
  }
}

tasks.register("ecj") {
  group = "ECJ"
  description = "Run all ECJ checks."
  dependsOn(tasks.withType<EclipseJavaCompile>())
}

@CacheableTask
abstract class EclipseJavaCompile @Inject constructor(
                                  @Internal val projectLayout: ProjectLayout) : JavaExec() {
  @get:InputFiles @get:CompileClasspath
  abstract val compileClasspath: Property<FileCollection>
  @get:InputFiles @get:PathSensitive(RELATIVE)
  abstract val javaSources: ConfigurableFileCollection
  @get:InputFile @get:PathSensitive(RELATIVE)
  abstract val properties: RegularFileProperty
  @get:OutputFile
  val report: Provider<RegularFile> = projectLayout.buildDirectory.file("reports/$name/report.txt")

  init {
    group = "ECJ"
    onlyIf { !javaSources.isEmpty }
    mainClass = "org.eclipse.jdt.internal.compiler.batch.Main"
    argumentProviders.add {
      val sources = javaSources.filter { it.name != "module-info.java" }.map { it.absolutePath }
      buildList {
        addAll(listOf(
          "-classpath", compileClasspath.get().filter { it.exists() }.asPath,
          "-properties", properties.absolutePath().get(),
          "-log", report.absolutePath().get(),
          "-encoding", "UTF-8",
          "-enableJavadoc",
          "-failOnWarning",
          "--release", "24",
          "-proc:none",
          "-d", "none"))
        addAll(sources)
      }
    }
    doLast {
      report.get().asFile.useLines { lines ->
        lines.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }.forEach { println(it) }
      }
    }
  }
}
