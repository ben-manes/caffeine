plugins {
  `java-library`
  id("biz.aQute.bnd.builder")
  id("pmd-caffeine-conventions")
  id("base-caffeine-conventions")
  id("jacoco-caffeine-conventions")
  id("publish-caffeine-conventions")
  id("testing-caffeine-conventions")
  id("spotbugs-caffeine-conventions")
  id("checkstyle-caffeine-conventions")
  id("errorprone-caffeine-conventions")
  id("object-layout-caffeine-conventions")
  id("forbidden-apis-caffeine-conventions")
}

dependencies {
  annotationProcessor(platform(libs.asm.bom))
  annotationProcessor(platform(libs.kotlin.bom))
}

java.toolchain.languageVersion = JavaLanguageVersion.of(
  System.getenv("JAVA_VERSION")?.toIntOrNull() ?: 11)

tasks.withType<JavaCompile>().configureEach {
  sourceCompatibility = java.toolchain.languageVersion.get().toString()
  targetCompatibility = java.toolchain.languageVersion.get().toString()
  options.release = java.toolchain.languageVersion.get().asInt()

  javaCompiler = javaToolchains.compilerFor {
    languageVersion = java.toolchain.languageVersion
  }

  options.compilerArgs.add("-Xlint:all,-processing,-exports,-auxiliaryclass,"
    + "-requires-automatic,-requires-transitive-automatic")
  options.compilerArgs.addAll(listOf("-Xmaxerrs", "500", "-Xmaxwarns", "500"))
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
  javadocOptions {
    links(
      "https://checkerframework.org/api/",
      "https://errorprone.info/api/latest/",
      "https://lightbend.github.io/config/latest/api/",
      "https://guava.dev/releases/${libs.versions.guava.get()}/api/docs/",
      "https://docs.oracle.com/en/java/javase/${java.toolchain.languageVersion.get()}/docs/api/")

    if (project != project(":caffeine")) {
      val caffeineJavadoc = project(":caffeine").tasks.named<Javadoc>("javadoc")
      linksOffline("https://static.javadoc.io/${group}/caffeine/${version}/",
        relativePath(caffeineJavadoc.get().destinationDir!!.path))
      dependsOn(caffeineJavadoc)
    }
  }
}
