plugins {
  idea
  `jvm-ecosystem`
  id("eclipse-caffeine-conventions")
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
  }
}
