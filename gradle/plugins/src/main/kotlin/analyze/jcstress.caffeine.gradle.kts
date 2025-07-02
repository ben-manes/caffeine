import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import net.ltgt.gradle.errorprone.errorprone

plugins {
  id("java-library.caffeine")
}

val jcstress = sourceSets.register("jcstress") {
  java.srcDir("src/jcstress/java")
}

val jcstressImplementation by configurations.existing
val jcstressRuntimeClasspath by configurations.existing
val jcstressAnnotationProcessor by configurations.existing

dependencies {
  jcstressAnnotationProcessor(libs.jcstress)

  jcstressImplementation(libs.errorprone)
  jcstressImplementation(libs.jcstress)
  jcstressImplementation(libs.jspecify)
}

val compileJcstressJava by tasks.existing(JavaCompile::class) {
  inputs.files(tasks.jar.map { it.archiveFile })
  options.apply {
    compilerArgs.add("-Xlint:-processing")
    errorprone.excludedPaths = ".*_jcstress.*"
  }
}

val jcstressJar = tasks.register<Jar>("jcstressJar") {
  archiveClassifier.set("jcstress")
  from(jcstress.map { it.output })
}

tasks.named<CheckForbiddenApis>("forbiddenApisJcstress").configure {
  bundledSignatures.addAll(listOf("jdk-deprecated", "jdk-internal",
    "jdk-non-portable", "jdk-reflection", "jdk-system-out", "jdk-unsafe"))
}

tasks.register<JCStress>("jcstress") {
  group = "Verification"
  description = "JCStress tests"
  classpath(jcstressRuntimeClasspath, jcstressJar.map { it.archiveFile })
  inputs.files(compileJcstressJava.map { it.outputs.files },
    jcstressJar.map { it.archiveFile }, tasks.jar.map { it.archiveFile })
  javaLauncher = javaToolchains.launcherFor { languageVersion = java.toolchain.languageVersion }
}

eclipse.classpath {
  plusConfigurations.add(configurations["jcstressCompileClasspath"])
}

@CacheableTask
abstract class JCStress : JavaExec() {
  @get:Input @get:Optional
  @get:Option(option = "mode", description = "[sanity, quick, default, tough, stress]")
  abstract val mode: Property<String>
  @get:Input @get:Optional
  @get:Option(option = "time", description = "Time per test iteration (milliseconds)")
  abstract val time: Property<String>
  @get:Input @get:Optional
  @get:Option(option = "iterations", description = "Iterations per test")
  abstract val iterations: Property<String>
  @get:Input @get:Optional
  @get:Option(option = "tests", description = "The tests to execute")
  abstract val testNames: Property<String>
  @get:OutputDirectory
  val outputDir: Provider<Directory> = project.layout.buildDirectory.dir("jcstress")

  init {
    jvmArgs("-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI", "-XX:-RestrictContended")
    workingDir(outputDir.map { it.asFile })
    mainClass = "org.openjdk.jcstress.Main"
  }

  @TaskAction
  override fun exec() {
    if (iterations.isPresent) {
      args("-iters", iterations.get().replace("[_,]".toRegex(), ""))
    }
    if (time.isPresent) {
      args("-time", time.get().replace("[_,]".toRegex(), ""))
    }
    if (testNames.isPresent) {
      args("-t", testNames.get())
    }
    if (mode.isPresent) {
      args("-m", mode.get())
    }
    args("-r", outputDir.get().asFile.resolve("results"))
    outputDir.get().asFile.mkdirs()
    super.exec()
  }
}
