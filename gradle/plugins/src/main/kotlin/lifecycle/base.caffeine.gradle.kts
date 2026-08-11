@file:Suppress("PackageDirectoryMismatch", "UnstableApiUsage")
plugins {
  idea
  `jvm-ecosystem`
  id("eclipse.caffeine")
  id("org.gradlex.jvm-dependency-conflict-resolution")
}

description = "A high performance caching library"
if (project != rootProject) {
  group = "com.github.ben-manes.caffeine"
}
if (version == Project.DEFAULT_VERSION) {
  val major = providers.gradleProperty("version.major").get()
  val minor = providers.gradleProperty("version.minor").get()
  val patch = providers.gradleProperty("version.patch").get()
  val releaseBuild = providers.gradleProperty("version.release").get().toBoolean()
  version = "$major.$minor.$patch" + if (releaseBuild) "" else "-SNAPSHOT"
}

dependencies {
  val ignored = listOf("api", "compileOnlyApi", "implementation", "javadocElements",
    "runtimeOnly", "sourcesElements", "caffeineJavadoc", "jacocoAggregation", "nmcpAggregation")
  configurations.configureEach {
    if ((name !in ignored) && isCanBeDeclared) {
      libs.bundles.constraints.get().forEach { library ->
        constraints.add(name, library.module.toString()).version { require(library.version!!) }
      }
    }
    resolutionStrategy.eachDependency {
      when (requested.group) {
        in libs.slf4j.bom.get().group -> useVersion(libs.versions.slf4j.asProvider().get())
      }
    }
  }
}

idea.module {
  isDownloadSources = true
  isDownloadJavadoc = true
  excludeDirs = derivedDirectories()
}

/** Returns the tool-generated files and directories within this project. */
private fun Project.derivedDirectories(): Set<File> {
  val derived = setOf(".classpath", ".gradle", ".kotlin",
    ".project", ".settings", "bin", "build", "out", "test-output")
  val found = mutableSetOf<File>()
  fun scan(directory: File, depth: Int) {
    for (file in directory.listFiles().orEmpty()) {
      if (file.name in derived) {
        found += file
      } else if (file.isDirectory && !file.name.startsWith(".") && (depth < 5)) {
        scan(file, depth + 1)
      }
    }
  }
  scan(projectDir, /* depth= */ 1)
  return found
}
