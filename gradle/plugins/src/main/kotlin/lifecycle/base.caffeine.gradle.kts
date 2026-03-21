@file:Suppress("PackageDirectoryMismatch", "UnstableApiUsage")
plugins {
  idea
  `jvm-ecosystem`
  id("eclipse.caffeine")
  id("org.gradlex.jvm-dependency-conflict-resolution")
}

dependencies {
  val ignored = listOf("api", "compileOnlyApi", "implementation",
    "javadocElements", "runtimeOnly", "sourcesElements")
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
