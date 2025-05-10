plugins {
  `java-library`
  id("ecj.caffeine")
  id("pmd.caffeine")
  id("base.caffeine")
  id("jacoco.caffeine")
  id("publish.caffeine")
  id("testing.caffeine")
  id("spotbugs.caffeine")
  id("checkstyle.caffeine")
  id("errorprone.caffeine")
  id("biz.aQute.bnd.builder")
  id("object-layout.caffeine")
  id("forbidden-apis.caffeine")
}

dependencies {
  annotationProcessor(platform(libs.asm.bom))
  annotationProcessor(platform(libs.kotlin.bom))
}

java.toolchain {
  languageVersion = JavaLanguageVersion.of(System.getenv("JAVA_VERSION")?.toIntOrNull() ?: 11)
  vendor = System.getenv("JAVA_VENDOR")?.let(JvmVendorSpec::of)
}
val javaRuntimeVersion: Provider<JavaLanguageVersion> =
  java.toolchain.languageVersion.map { maxOf(it, JavaLanguageVersion.of(24)) }

tasks.withType<JavaCompile>().configureEach {
  inputs.property("javaDistribution",
    providers.environmentVariable("JDK_DISTRIBUTION")).optional(true)
  inputs.property("javaVendor", java.toolchain.vendor.map { it.toString() })
  options.release = java.toolchain.languageVersion.map { it.asInt() }

  javaCompiler = javaToolchains.compilerFor {
    languageVersion = javaRuntimeVersion
  }

  options.apply {
    javaModuleVersion = provider { version as String }
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
    languageVersion = javaRuntimeVersion
  }
}

tasks.withType<AbstractArchiveTask>().configureEach {
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
  filePermissions {
    unix("rw-r--r--")
  }
  dirPermissions {
    unix("rwxr-xr-x")
  }
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
  isFailOnError = false
  javadocOptions {
    use()
    quiet()
    noTimestamp()
    addStringOption("-release", java.toolchain.languageVersion.get().toString())
    addStringOption("-link-modularity-mismatch", "info")
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
      inputs.files(caffeine.tasks.named<Javadoc>("javadoc").map { it.outputs.files })
    }
  }
  javadocTool = javaToolchains.javadocToolFor {
    languageVersion = javaRuntimeVersion
  }
}
