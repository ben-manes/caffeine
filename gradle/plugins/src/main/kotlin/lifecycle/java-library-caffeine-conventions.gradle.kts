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

java.toolchain.languageVersion.set(JavaLanguageVersion.of(System.getenv("JAVA_VERSION") ?: "11"))

tasks.withType<JavaCompile>().configureEach {
  sourceCompatibility = java.toolchain.languageVersion.get().toString()
  targetCompatibility = java.toolchain.languageVersion.get().toString()
  options.release.set(java.toolchain.languageVersion.get().asInt())

  javaCompiler.set(javaToolchains.compilerFor {
    languageVersion.set(java.toolchain.languageVersion)
  })

  options.compilerArgs.add("-Xlint:all,-processing,-exports,-auxiliaryclass,"
    + "-requires-automatic,-requires-transitive-automatic")
  options.compilerArgs.addAll(listOf("-Xmaxerrs", "500", "-Xmaxwarns", "500"))
  options.encoding = "UTF-8"
}

tasks.withType<JavaExec>().configureEach {
  jvmArgs(DisableStrongEncapsulationJvmArgs)
  javaLauncher.set(javaToolchains.launcherFor {
    languageVersion.set(java.toolchain.languageVersion)
  })
}

tasks.withType<AbstractArchiveTask>().configureEach {
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
  fileMode = "664".toInt(8)
  dirMode = "775".toInt(8)
}

val projectDescription = objects.property<String>().convention(provider { project.description })
tasks.jar {
  inputs.property("version", project.version.toString())
  outputs.cacheIf { true }
  metaInf {
    from("$rootDir/LICENSE")
  }
  bundle {
    properties.set(projectDescription.map {
      mapOf("project.description" to it)
    })
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
