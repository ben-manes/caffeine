@file:Suppress("PackageDirectoryMismatch", "UnstableApiUsage")
import org.gradle.api.tasks.PathSensitivity.RELATIVE

plugins {
  `java-library`
  id("ecj.caffeine")
  id("pmd.caffeine")
  id("base.caffeine")
  id("jacoco.caffeine")
  id("publish.caffeine")
  id("testing.caffeine")
  id("spotbugs.caffeine")
  id("errorprone.caffeine")
  id("biz.aQute.bnd.builder")
  id("object-layout.caffeine")
  id("forbidden-apis.caffeine")
  id("dependency-analysis.caffeine")
}

dependencies {
  annotationProcessor(platform(libs.asm.bom))
  annotationProcessor(platform(libs.kotlin.bom))
}

java.toolchain {
  languageVersion = javaVersion()
  if (javaVendor().isPresent) {
    vendor = javaVendor()
  }
  nativeImageCapable = isGraalVM()
}
val javaRuntimeVersion = javaRuntimeVersion()

tasks.withType<JavaCompile>().configureEach {
  inputs.property("javaVendor", java.toolchain.vendor.map { it.toString() })
  inputs.property("javaDistribution", javaDistribution()).optional(true)
  options.release = java.toolchain.languageVersion.map { it.asInt() }

  javaCompiler = javaToolchains.compilerFor {
    vendor = java.toolchain.vendor
    languageVersion = javaRuntimeVersion
    implementation = java.toolchain.implementation
    nativeImageCapable = java.toolchain.nativeImageCapable
  }

  options.apply {
    javaModuleVersion = provider {
      version.toString().takeIf { it.first().isDigit() } ?: "0.0.0-$version"
    }
    compilerArgs.addAll(listOf("-Xlint:all", "-parameters",
      "-Xmaxerrs", "500", "-Xmaxwarns", "500"))
    val failOnWarnings = isCI()
    compilerArgumentProviders.add {
      if (failOnWarnings.get()) listOf("-Werror") else emptyList()
    }
    encoding = "UTF-8"
  }
}

tasks.withType<JavaExec>().configureEach {
  jvmArgs(DisableStrongEncapsulationJvmArgs)
  javaLauncher = javaToolchains.launcherFor {
    vendor = java.toolchain.vendor
    languageVersion = javaRuntimeVersion
    implementation = java.toolchain.implementation
    nativeImageCapable = java.toolchain.nativeImageCapable
  }
}

tasks.withType<AbstractCodeQualityTask>().configureEach {
  javaLauncher = javaToolchains.launcherFor {
    vendor = java.toolchain.vendor
    languageVersion = javaRuntimeVersion
    implementation = java.toolchain.implementation
    nativeImageCapable = java.toolchain.nativeImageCapable
  }
}

tasks.named("assemble") {
  dependsOn(tasks.withType<Jar>())
  dependsOn(tasks.withType<JavaCompile>())
}

tasks.named<Jar>("jar").configure {
  inputs.property("version", project.version.toString())
  outputs.cacheIf { true }
  metaInf {
    from("$rootDir/LICENSE")
  }
  bundle {
    properties.empty()
    bnd(mapOf(
      "Bundle-License" to "https://www.apache.org/licenses/LICENSE-2.0",
      "Build-Jdk-Spec" to java.toolchain.languageVersion.get(),
      "Implementation-Title" to project.description,
      "Bundle-Description" to project.description,
      "Implementation-Version" to version,
      "-noextraheaders" to true,
      "-reproducible" to true,
      "-snapshot" to "SNAPSHOT"))
  }
}

tasks.withType<Javadoc>().configureEach {
  val snippetPath = layout.projectDirectory.dir("src/test/java")
  val isEnabled = isEarlyAccess().map { !it }
  onlyIf { isEnabled.get() }
  inputs.dir(snippetPath)
    .withPathSensitivity(RELATIVE)
    .withPropertyName("snippetPath")
  javadocOptions {
    use()
    noTimestamp()
    addStringOption("-link-modularity-mismatch", "info")
    addStringOption("-snippet-path", snippetPath.asFile.absolutePath)
    addStringOption("-release", java.toolchain.languageVersion.get().toString())
    links(
      "https://jspecify.dev/docs/api/",
      "https://errorprone.info/api/latest/",
      "https://lightbend.github.io/config/latest/api/",
      "https://guava.dev/releases/${libs.versions.guava.get()}/api/docs/",
      "https://docs.oracle.com/en/java/javase/${java.toolchain.languageVersion.get()}/docs/api/")
    val caffeine = project(":caffeine")
    if (project != caffeine) {
      linksOffline("https://static.javadoc.io/$group/caffeine/$version/",
        relativePath(caffeine.layout.buildDirectory.dir("docs/javadoc")))
      inputs.files(caffeine.tasks.withType<Javadoc>().map { it.outputs.files })
    }
  }
  javadocTool = javaToolchains.javadocToolFor {
    vendor = java.toolchain.vendor
    languageVersion = javaRuntimeVersion
    implementation = java.toolchain.implementation
    nativeImageCapable = java.toolchain.nativeImageCapable
  }
}
