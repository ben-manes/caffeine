import org.gradle.api.internal.artifacts.configurations.DefaultConfiguration

plugins {
  idea
  `jvm-ecosystem`
  id("eclipse-caffeine-conventions")
}

apply(from = "${rootDir}/gradle/constraints.gradle.kts")
val restrictions: List<MinimalExternalModuleDependency> by extra

dependencies {
  val ignored = listOf("api", "compileOnlyApi", "implementation",
    "javadocElements", "runtimeOnly", "sourcesElements")
  configurations.configureEach {
    if ((name !in ignored) && (this is DefaultConfiguration) && isCanBeDeclaredAgainst) {
      restrictions.forEach { library ->
        constraints.add(name, library.module.toString()).version { require(library.version!!) }
      }
    }
  }
}
