plugins {
  `java-library`
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

val javaVersion = JavaLanguageVersion.of(System.getenv("JAVA_VERSION")?.toIntOrNull() ?: 11)
val javaVendor = System.getenv("JAVA_VENDOR")?.let { JvmVendorSpec.matching(it) }
val javaRuntimeVersion = maxOf(javaVersion, JavaLanguageVersion.of(21))
java.toolchain {
  languageVersion = javaVersion
  vendor = javaVendor
}

tasks.withType<JavaCompile>().configureEach {
  inputs.property("javaVendor", javaVendor.toString())
  sourceCompatibility = javaVersion.toString()
  targetCompatibility = javaVersion.toString()
  options.release = javaVersion.asInt()

  javaCompiler = javaToolchains.compilerFor {
    // jdk 17+ is required by compiler plugins, e.g. error-prone
    languageVersion = javaRuntimeVersion
  }

  options.apply {
    javaModuleVersion = provider { version as String }
    compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-auxiliaryclass", "-Xlint:-classfile",
      "-Xlint:-exports", "-Xlint:-processing", "-Xlint:-removal", "-Xlint:-requires-automatic",
      "-parameters", "-Xmaxerrs", "500", "-Xmaxwarns", "500"))
    if (isCI()) {
      compilerArgs.add("-Werror")
    }
    if (javaVersion.canCompileOrRun(21)) {
      compilerArgs.add("-proc:full")
    }
    encoding = "UTF-8"
  }
}

tasks.withType<JavaExec>().configureEach {
  jvmArgs(DisableStrongEncapsulationJvmArgs)
  javaLauncher = javaToolchains.launcherFor {
    // jdk 17+ is required by dependencies, e.g. google-java-format
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

tasks.jar {
  inputs.property("version", project.version.toString())
  outputs.cacheIf { true }
  metaInf {
    from("$rootDir/LICENSE")
  }
  bundle {
    properties.empty()
    bnd(mapOf(
      "Bundle-License" to "https://www.apache.org/licenses/LICENSE-2.0",
      "Implementation-Title" to project.description,
      "Bundle-Description" to project.description,
      "Implementation-Version" to version,
      "Build-Jdk-Spec" to javaVersion,
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
    addStringOption("-release", javaVersion.toString())
    addStringOption("-link-modularity-mismatch", "info")
    links(
      "https://jspecify.dev/docs/api/",
      "https://errorprone.info/api/latest/",
      "https://lightbend.github.io/config/latest/api/",
      "https://docs.oracle.com/en/java/javase/$javaVersion/docs/api/",
      "https://guava.dev/releases/${libs.versions.guava.get()}/api/docs/")

    if (project != project(":caffeine")) {
      linksOffline("https://static.javadoc.io/$group/caffeine/$version/",
        relativePath(project(":caffeine").layout.buildDirectory.dir("docs/javadoc")))
      dependsOn(":caffeine:javadoc")
    }
  }
  javadocTool = javaToolchains.javadocToolFor {
    languageVersion = javaRuntimeVersion
  }
}
