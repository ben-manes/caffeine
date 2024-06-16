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
java.toolchain.languageVersion = javaVersion

tasks.withType<JavaCompile>().configureEach {
  sourceCompatibility = javaVersion.toString()
  targetCompatibility = javaVersion.toString()
  options.release = javaVersion.asInt()

  javaCompiler = javaToolchains.compilerFor {
    // jdk 17+ is required by compiler plugins, e.g. error-prone
    languageVersion = maxOf(javaVersion, JavaLanguageVersion.of(17))
  }

  options.compilerArgs.add("-Xlint:all,-auxiliaryclass,-exports,-processing,"
    + "-removal,-requires-automatic,-requires-transitive-automatic")
  options.compilerArgs.addAll(listOf("-Xmaxerrs", "500", "-Xmaxwarns", "500"))
  if (javaVersion.canCompileOrRun(21)) {
    options.compilerArgs.add("-proc:full")
  }
  options.encoding = "UTF-8"
}

tasks.withType<JavaExec>().configureEach {
  jvmArgs(DisableStrongEncapsulationJvmArgs)
  javaLauncher = javaToolchains.launcherFor {
    languageVersion = java.toolchain.languageVersion
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
    links(
      "https://checkerframework.org/api/",
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
}
