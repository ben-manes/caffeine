import org.gradle.api.internal.artifacts.configurations.DefaultConfiguration

plugins {
  idea
  `jvm-ecosystem`
  id("eclipse-caffeine-conventions")
}

dependencies {
  val ignored = listOf("api", "compileOnlyApi", "implementation",
    "javadocElements", "runtimeOnly", "sourcesElements")
  configurations.configureEach {
    if ((name !in ignored) && (this is DefaultConfiguration) && isCanBeDeclaredAgainst) {
      libs.bundles.restrictions.get().forEach { library ->
        constraints.add(name, library.module.toString()).version { require(library.version!!) }
      }
    }
  }
}
