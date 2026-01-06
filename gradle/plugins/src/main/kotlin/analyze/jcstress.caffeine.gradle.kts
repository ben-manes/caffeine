@file:Suppress("PackageDirectoryMismatch")
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

  jcstressImplementation(libs.jcstress)
  jcstressImplementation(libs.jspecify)
  jcstressImplementation(libs.errorprone.annotations)
  jcstressImplementation(files(tasks.jar.map { it.archiveFile }))
}

val compileJcstressJava by tasks.existing(JavaCompile::class) {
  options.apply {
    compilerArgs.add("-Xlint:-processing")
    errorprone.excludedPaths = ".*_jcstress.*"
  }
}

val jcstressJar = tasks.register<Jar>("jcstressJar") {
  archiveClassifier.set("jcstress")
  from(jcstress.map { it.output })
}

tasks.register<JCStress>("jcstress") {
  group = "Verification"
  description = "JCStress tests"
  classpath(jcstressRuntimeClasspath, jcstressJar.map { it.archiveFile })
  inputs.files(compileJcstressJava.map { it.outputs.files },
    jcstressJar.map { it.archiveFile }, tasks.jar.map { it.archiveFile })
  val javaVersion = java.toolchain.languageVersion.map { it.asInt() }
  jvmArgumentProviders.add {
    if (javaVersion.get() >= 25) listOf("-XX:+UseCompactObjectHeaders") else emptyList()
  }
  javaLauncher = javaToolchains.launcherFor {
    vendor = java.toolchain.vendor
    implementation = java.toolchain.implementation
    languageVersion = java.toolchain.languageVersion
    nativeImageCapable = java.toolchain.nativeImageCapable
  }
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
    argumentProviders.add {
      buildList {
        if (iterations.isPresent) {
          addAll(listOf("-iters", iterations.get().replace("[_,]".toRegex(), "")))
        }
        if (time.isPresent) {
          addAll(listOf("-time", time.get().replace("[_,]".toRegex(), "")))
        }
        if (testNames.isPresent) {
          addAll(listOf("-t", testNames.get()))
        }
        if (mode.isPresent) {
          addAll(listOf("-m", mode.get()))
        }
        addAll(listOf("-r", outputDir.get().asFile.resolve("results").path))
      }
    }
    doFirst {
      outputDir.get().asFile.mkdirs()
    }
  }
}
